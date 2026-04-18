package com.evilale;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP forward server. Accepts incoming connections and forwards each one to a remote host.
 * Connections are persistent and bidirectional until either side closes.
 */
public final class TCPForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TCPForwarder.class);

    /**
     * Accept timeout in milliseconds. Lets the accept loop periodically check the shutdown flag
     * without blocking indefinitely.
     */
    private static final int ACCEPT_TIMEOUT_MS = 1000;

    /**
     * Backlog queue length for the server socket.
     */
    private static final int BACKLOG = 5;

    /**
     * Grace period for in-flight forwarders to finish after stop() is called.
     */
    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final ForwarderConfig config;
    private final AtomicBoolean shutdownFlag;
    private final Set<Socket> activeSockets;
    private final ExecutorService executor;

    private volatile ServerSocket serverSocket;

    public TCPForwarder(ForwarderConfig config) {
        this.config = config;
        this.shutdownFlag = new AtomicBoolean(false);
        this.activeSockets = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("forwarder-worker"));
    }

    /**
     * Starts the forwarder. Blocks on the accept loop until {@link #stop()} is called or the
     * process is interrupted.
     */
    public void start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.getLocalHost(), config.getLocalPort()), BACKLOG);
            serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MS);

            LOGGER.info("Forwarder listening on {}:{}", config.getLocalHost(), config.getLocalPort());
            LOGGER.info("Forwarding traffic to {}:{}", config.getRemoteHost(), config.getRemotePort());

            acceptLoop();

        } catch (IOException e) {
            if (!shutdownFlag.get()) {
                LOGGER.error("Server error: {}", e.getMessage(), e);
            }
        } finally {
            closeServerSocket();
            LOGGER.info("Server socket closed");
        }
    }

    private void acceptLoop() {
        while (!shutdownFlag.get()) {
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch (SocketTimeoutException e) {
                continue; // expected, allows shutdown flag check
            } catch (IOException e) {
                if (!shutdownFlag.get()) {
                    LOGGER.error("Accept error: {}", e.getMessage());
                }
                break;
            }

            try {
                clientSocket.setTcpNoDelay(true);
            } catch (IOException e) {
                LOGGER.warn("Failed to set TCP_NODELAY on client socket: {}", e.getMessage());
            }

            activeSockets.add(clientSocket);

            InetSocketAddress addr = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
            LOGGER.info("Connection received from {}:{}",
                    addr.getAddress().getHostAddress(), addr.getPort());

            executor.submit(new ClientHandler(
                    clientSocket, config, shutdownFlag, activeSockets, executor));
        }
    }

    /**
     * Signals shutdown and closes all tracked sockets. Safe to call from a shutdown hook.
     */
    public void stop() {
        if (!shutdownFlag.compareAndSet(false, true)) {
            return; // already stopping
        }

        closeServerSocket();

        // Close all active sockets to unblock any in-flight read()s
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        activeSockets.clear();

        // Drain the executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("All sockets have been closed");
    }

    private void closeServerSocket() {
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Thread factory that produces daemon threads with a readable name.
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
