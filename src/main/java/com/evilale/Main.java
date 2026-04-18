package com.evilale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the TCP forward application.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
        // utility
    }

    public static void main(String[] args) {
        ForwarderConfig config;
        try {
            config = ForwarderConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            ForwarderConfig.printUsage();
            System.exit(1);
            return;
        }

        final TCPForwarder forwarder = new TCPForwarder(config);

        // Register shutdown hook to catch SIGINT (Ctrl+C) and SIGTERM for clean shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("Shutdown signal received, stopping forwarder...");
                forwarder.stop();
            }
        }, "shutdown-hook"));

        forwarder.start();
    }
}
