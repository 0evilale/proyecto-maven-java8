package com.evilale;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a single client connection: opens a socket to the remote host and spawns two
 * {@link DataForwarder} tasks for bidirectional forwarding.
 */
public final class ClientHandler implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket clientSocket;
    private final ForwarderConfig config;
    private final AtomicBoolean shutdownFlag;
    private final Set<Socket> activeSockets;
    private final ExecutorService executor;

    public ClientHandler(Socket clientSocket,
                         ForwarderConfig config,
                         AtomicBoolean shutdownFlag,
                         Set<Socket> activeSockets,
                         ExecutorService executor) {
        this.clientSocket = clientSocket;
        this.config = config;
        this.shutdownFlag = shutdownFlag;
        this.activeSockets = activeSockets;
        this.executor = executor;
    }

    @Override
    public void run() {
        final long startTime = System.currentTimeMillis();
        final String clientId = formatClientId(clientSocket);
        Socket remoteSocket = null;

        try {
            remoteSocket = openRemoteSocket();
            activeSockets.add(remoteSocket);
            LOGGER.info("Connected to remote server {}:{}",
                    config.getRemoteHost(), config.getRemotePort());

            String clientPrefix = "[CLIENT " + clientId + " -> SERVER]";
            String serverPrefix = "[SERVER -> CLIENT " + clientId + "]";

            DataForwarder clientToRemote = new DataForwarder(
                    clientSocket, remoteSocket, clientPrefix,
                    shutdownFlag, config);

            DataForwarder remoteToClient = new DataForwarder(
                    remoteSocket, clientSocket, serverPrefix,
                    shutdownFlag, config);

            Future<?> f1 = executor.submit(clientToRemote);
            Future<?> f2 = executor.submit(remoteToClient);

            // Wait for both forwarders to finish. Persistent by design, no join timeout.
            awaitQuietly(f1);
            awaitQuietly(f2);

        } catch (IOException e) {
            LOGGER.error("Connection error: {}", e.getMessage());
        } finally {
            if (remoteSocket != null) {
                activeSockets.remove(remoteSocket);
                closeSocketQuietly(remoteSocket);
            }
            activeSockets.remove(clientSocket);
            closeSocketQuietly(clientSocket);

            double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
            LOGGER.info("Connection closed (duration: {} seconds)",
                    String.format("%.2f", elapsedSeconds));
        }
    }

    private Socket openRemoteSocket() throws IOException {
        Socket socket = new Socket();
        socket.setReuseAddress(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(config.getRemoteHost(), config.getRemotePort()));
        return socket;
    }

    private static String formatClientId(Socket socket) {
        InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.debug("Forwarder task ended with exception: {}", e.getCause().getMessage());
        }
    }

    private static void closeSocketQuietly(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        // Shutdown both halves to flush any pending data before closing, mirroring the
        // shutdown(SHUT_RDWR) behaviour of the Python version.
        try {
            if (!socket.isInputShutdown()) {
                socket.shutdownInput();
            }
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            if (!socket.isOutputShutdown()) {
                socket.shutdownOutput();
            }
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
