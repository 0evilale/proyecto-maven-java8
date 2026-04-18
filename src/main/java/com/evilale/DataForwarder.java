package com.evilale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards bytes from a source socket to a destination socket until EOF, error, or shutdown.
 */
public final class DataForwarder implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataForwarder.class);

    private static final int BUFFER_SIZE = 4096;
    private static final int SOCKET_READ_TIMEOUT_MS = 1000;
    private static final int TEXT_LOG_LIMIT = 500;

    private final Socket source;
    private final Socket destination;
    private final String logPrefix;
    private final AtomicBoolean shutdownFlag;
    private final ForwarderConfig config;

    public DataForwarder(Socket source,
                         Socket destination,
                         String logPrefix,
                         AtomicBoolean shutdownFlag,
                         ForwarderConfig config) {
        this.source = source;
        this.destination = destination;
        this.logPrefix = logPrefix;
        this.shutdownFlag = shutdownFlag;
        this.config = config;
    }

    @Override
    public void run() {
        final long startTime = System.currentTimeMillis();

        try {
            source.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();

            if ("length-prefixed".equals(config.getProtocol())) {
                forwardLengthPrefixed(in, out, startTime);
            } else {
                forwardRaw(in, out, startTime);
            }
        } catch (IOException e) {
            LOGGER.debug("{} Socket error (broken connection): {}",
                    logPrefix, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("{} Unexpected error: {}", logPrefix, e.getMessage(), e);
        } finally {
            try {
                if (!destination.isClosed() && !destination.isOutputShutdown()) {
                    destination.shutdownOutput();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void forwardRaw(InputStream in, OutputStream out, long startTime) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        while (!shutdownFlag.get()) {
            if (connectionTimeoutReached(startTime)) {
                LOGGER.info("{} Connection timeout reached ({}s)",
                        logPrefix, config.getTimeoutSeconds());
                break;
            }

            int n;
            try {
                n = in.read(buffer);
            } catch (SocketTimeoutException e) {
                continue;
            }

            if (n < 0) {
                LOGGER.debug("{} Connection closed (EOF)", logPrefix);
                break;
            }
            if (n == 0) {
                continue;
            }

            LOGGER.info("{} Received {} bytes", logPrefix, n);
            logPayload(buffer, n);

            out.write(buffer, 0, n);
            out.flush();

            LOGGER.debug("{} Forwarded {} bytes", logPrefix, n);
        }
    }

    private void forwardLengthPrefixed(InputStream in, OutputStream out, long startTime) throws IOException {
        int prefixSize = config.getPrefixSize();
        boolean bigEndian = "big".equals(config.getPrefixEndianness());
        boolean includesHeader = config.isPrefixIncludesHeader();

        while (!shutdownFlag.get()) {
            if (connectionTimeoutReached(startTime)) {
                LOGGER.info("{} Connection timeout reached ({}s)",
                        logPrefix, config.getTimeoutSeconds());
                break;
            }

            byte[] prefixBuffer = new byte[prefixSize];
            int read = readWithTimeout(in, prefixBuffer, 0, prefixSize, startTime);
            if (read < 0) {
                LOGGER.debug("{} Connection closed (EOF)", logPrefix);
                break;
            }
            if (read == 0) {
                continue;
            }
            if (read < prefixSize) {
                LOGGER.warn("{} Incomplete prefix received: {} bytes", logPrefix, read);
                break;
            }

            int length = decodeLength(prefixBuffer, prefixSize, bigEndian);
            int payloadSize = includesHeader ? length - prefixSize : length;

            if (payloadSize < 0 || payloadSize > 50 * 1024 * 1024) { // 50MB sanity check
                LOGGER.warn("{} Invalid payload size decoded: {}", logPrefix, payloadSize);
                break;
            }

            byte[] payloadBuffer = new byte[payloadSize];
            int payloadRead = 0;
            if (payloadSize > 0) {
                payloadRead = readWithTimeout(in, payloadBuffer, 0, payloadSize, startTime);
                if (payloadRead < 0) {
                    LOGGER.debug("{} Connection closed (EOF) during payload read", logPrefix);
                    break;
                }
                if (payloadRead < payloadSize) {
                    LOGGER.warn("{} Incomplete payload received: {}/{}", logPrefix, payloadRead, payloadSize);
                    break;
                }
            }

            int totalSize = prefixSize + payloadSize;
            byte[] fullMessage = new byte[totalSize];
            System.arraycopy(prefixBuffer, 0, fullMessage, 0, prefixSize);
            if (payloadSize > 0) {
                System.arraycopy(payloadBuffer, 0, fullMessage, prefixSize, payloadSize);
            }

            LOGGER.info("{} Received message of {} bytes (payload {})", logPrefix, totalSize, payloadSize);
            logPayload(fullMessage, totalSize);
            out.write(fullMessage);
            out.flush();
            LOGGER.debug("{} Forwarded message of {} bytes", logPrefix, totalSize);
        }
    }

    private int readWithTimeout(InputStream in, byte[] buffer, int offset, int length, long startTime) throws IOException {
        int totalRead = 0;
        while (totalRead < length && !shutdownFlag.get()) {
            if (connectionTimeoutReached(startTime)) {
                break;
            }
            try {
                int n = in.read(buffer, offset + totalRead, length - totalRead);
                if (n < 0) {
                    return totalRead == 0 ? -1 : totalRead;
                }
                totalRead += n;
            } catch (SocketTimeoutException e) {
                if (totalRead == 0) {
                    return 0;
                }
            }
        }
        return totalRead;
    }

    private int decodeLength(byte[] prefix, int size, boolean bigEndian) {
        int len = 0;
        if (bigEndian) {
            for (int i = 0; i < size; i++) {
                len = (len << 8) | (prefix[i] & 0xFF);
            }
        } else {
            for (int i = 0; i < size; i++) {
                len |= (prefix[i] & 0xFF) << (8 * i);
            }
        }
        return len;
    }

    private boolean connectionTimeoutReached(long startTime) {
        if (config.getTimeoutSeconds() <= 0) {
            return false;
        }
        long elapsedMs = System.currentTimeMillis() - startTime;
        return elapsedMs > (long) config.getTimeoutSeconds() * 1000L;
    }

    private void logPayload(byte[] buffer, int length) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug("{} Hex: {}", logPrefix, toHex(buffer, length));

        String text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        if (!text.trim().isEmpty()) {
            String truncated = text.length() > TEXT_LOG_LIMIT
                    ? text.substring(0, TEXT_LOG_LIMIT)
                    : text;
            LOGGER.debug("{} Text: {}", logPrefix, truncated);
        }
    }

    private static String toHex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = data[i] & 0xff;
            if (b < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }
}
