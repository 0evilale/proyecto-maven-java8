package com.evilale;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Immutable configuration for the TCP forward, built from a .properties file and/or
 * command-line arguments. CLI arguments always take precedence over file values.
 */
public final class ForwarderConfig {

    private final String localHost;
    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private final int timeoutSeconds;
    private final String protocol;
    private final int prefixSize;
    private final String prefixEndianness;
    private final boolean prefixIncludesHeader;

    private ForwarderConfig(String localHost, int localPort, String remoteHost, int remotePort, int timeoutSeconds,
                            String protocol, int prefixSize, String prefixEndianness, boolean prefixIncludesHeader) {
        this.localHost = localHost;
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.timeoutSeconds = timeoutSeconds;
        this.protocol = protocol;
        this.prefixSize = prefixSize;
        this.prefixEndianness = prefixEndianness;
        this.prefixIncludesHeader = prefixIncludesHeader;
    }

    public String getLocalHost() {
        return localHost;
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    /**
     * @return Max connection duration in seconds, or 0 for persistent connections with no timeout.
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getProtocol() {
        return protocol;
    }

    public int getPrefixSize() {
        return prefixSize;
    }

    public String getPrefixEndianness() {
        return prefixEndianness;
    }

    public boolean isPrefixIncludesHeader() {
        return prefixIncludesHeader;
    }

    /**
     * Parses command-line arguments into a ForwarderConfig instance.
     * <p>
     * If {@code --config <file>} is present, the .properties file is loaded first as the base
     * configuration. Any other CLI arguments override the file values.
     *
     * @throws IllegalArgumentException if required arguments are missing or malformed.
     */
    public static ForwarderConfig parse(String[] args) {
        // --- Pass 1: look for --config to load the base properties ---
        Properties props = new Properties();
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for --config");
                }
                String path = args[++i];
                try (FileInputStream fis = new FileInputStream(path)) {
                    props.load(fis);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot read config file '" + path + "': " + e.getMessage());
                }
                break;
            }
        }

        // --- Seed defaults from properties file (or built-in defaults if not set) ---
        String localHost = props.getProperty("local.host", "127.0.0.1");
        Integer localPort = parseOptionalPort(props, "local.port");
        String remoteHost = props.getProperty("remote.host");
        Integer remotePort = parseOptionalPort(props, "remote.port");
        int timeoutSeconds = parseOptionalNonNegativeInt(props, "timeout", 0);
        String protocol = props.getProperty("protocol", "raw");
        int prefixSize = parseOptionalNonNegativeInt(props, "prefix.size", 2);
        String prefixEndianness = props.getProperty("prefix.endianness", "big");
        boolean prefixIncludesHeader = Boolean.parseBoolean(
                props.getProperty("prefix.includes.header", "false"));

        // Validate values loaded from file using the same rules as CLI
        if (props.containsKey("protocol") && !protocol.equals("raw") && !protocol.equals("length-prefixed")) {
            throw new IllegalArgumentException("'protocol' in config file must be 'raw' or 'length-prefixed'");
        }
        if (props.containsKey("prefix.size") && (prefixSize < 1 || prefixSize > 4)) {
            throw new IllegalArgumentException("'prefix.size' in config file must be between 1 and 4");
        }
        if (props.containsKey("prefix.endianness")
                && !prefixEndianness.equals("big") && !prefixEndianness.equals("little")) {
            throw new IllegalArgumentException("'prefix.endianness' in config file must be 'big' or 'little'");
        }

        // --- Pass 2: apply CLI arguments (override file values) ---
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--config":
                    i++; // already processed in pass 1, skip its value
                    break;
                case "--local-host":
                    localHost = requireValue(args, ++i, arg);
                    break;
                case "--local-port":
                    localPort = parsePort(requireValue(args, ++i, arg), arg);
                    break;
                case "--remote-host":
                    remoteHost = requireValue(args, ++i, arg);
                    break;
                case "--remote-port":
                    remotePort = parsePort(requireValue(args, ++i, arg), arg);
                    break;
                case "--timeout":
                    timeoutSeconds = parseNonNegativeInt(requireValue(args, ++i, arg), arg);
                    break;
                case "--protocol":
                    protocol = requireValue(args, ++i, arg);
                    if (!protocol.equals("raw") && !protocol.equals("length-prefixed")) {
                        throw new IllegalArgumentException("Protocol must be 'raw' or 'length-prefixed'");
                    }
                    break;
                case "--prefix-size":
                    prefixSize = parseNonNegativeInt(requireValue(args, ++i, arg), arg);
                    if (prefixSize < 1 || prefixSize > 4) {
                        throw new IllegalArgumentException("Prefix size must be between 1 and 4");
                    }
                    break;
                case "--prefix-endianness":
                    prefixEndianness = requireValue(args, ++i, arg);
                    if (!prefixEndianness.equals("big") && !prefixEndianness.equals("little")) {
                        throw new IllegalArgumentException("Prefix endianness must be 'big' or 'little'");
                    }
                    break;
                case "--prefix-includes-header":
                    prefixIncludesHeader = true;
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        // --- Final validation: required fields ---
        if (localPort == null) {
            throw new IllegalArgumentException("local.port / --local-port is required");
        }
        if (remoteHost == null) {
            throw new IllegalArgumentException("remote.host / --remote-host is required");
        }
        if (remotePort == null) {
            throw new IllegalArgumentException("remote.port / --remote-port is required");
        }

        return new ForwarderConfig(localHost, localPort, remoteHost, remotePort, timeoutSeconds,
                protocol, prefixSize, prefixEndianness, prefixIncludesHeader);
    }

    public static void printUsage() {
        System.out.println("Usage: java -jar tcp-forward.jar [--config <file.properties>] [options]");
        System.out.println();
        System.out.println("Options (CLI always overrides values from --config file):");
        System.out.println("  --config FILE              Load configuration from a .properties file");
        System.out.println("  --local-host HOST          Local host to listen on (default: 127.0.0.1)");
        System.out.println("  --local-port PORT          Local port to listen on (required)");
        System.out.println("  --remote-host HOST         Remote host to forward to (required)");
        System.out.println("  --remote-port PORT         Remote port to forward to (required)");
        System.out.println("  --timeout SECONDS          Max connection duration in seconds");
        System.out.println("                             (default: 0 = persistent / no timeout)");
        System.out.println("  --protocol TYPE            Protocol type: raw or length-prefixed (default: raw)");
        System.out.println("  --prefix-size BYTES        Size of length prefix: 1 to 4 (default: 2)");
        System.out.println("  --prefix-endianness ORDER  Endianness of prefix: big or little (default: big)");
        System.out.println("  --prefix-includes-header   Set if length includes the prefix size itself");
        System.out.println("  -h, --help                 Show this help and exit");
        System.out.println();
        System.out.println("Properties file keys:");
        System.out.println("  local.host              (default: 127.0.0.1)");
        System.out.println("  local.port              (required if not passed via CLI)");
        System.out.println("  remote.host             (required if not passed via CLI)");
        System.out.println("  remote.port             (required if not passed via CLI)");
        System.out.println("  timeout                 (default: 0)");
        System.out.println("  protocol                raw | length-prefixed  (default: raw)");
        System.out.println("  prefix.size             1-4  (default: 2)");
        System.out.println("  prefix.endianness       big | little  (default: big)");
        System.out.println("  prefix.includes.header  true | false  (default: false)");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Integer parseOptionalPort(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) {
            return null;
        }
        return parsePort(value.trim(), key);
    }

    private static int parseOptionalNonNegativeInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return parseNonNegativeInt(value.trim(), key);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    private static int parsePort(String value, String flag) {
        int port = parseNonNegativeInt(value, flag);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(flag + " must be between 1 and 65535, got: " + port);
        }
        return port;
    }

    private static int parseNonNegativeInt(String value, String flag) {
        try {
            int n = Integer.parseInt(value);
            if (n < 0) {
                throw new IllegalArgumentException(flag + " must be non-negative, got: " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be a number, got: " + value);
        }
    }
}
