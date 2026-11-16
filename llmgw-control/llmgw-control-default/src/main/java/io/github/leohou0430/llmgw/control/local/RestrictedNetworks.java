package io.github.leohou0430.llmgw.control.local;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allowlist for migration-mode admin clients: comma-separated IPv4 CIDRs or exact
 * addresses. Loopback is decided by {@link DefaultAdminTokenPolicy}; this class only adds
 * the operator-configured restricted admin network. Invalid entries fail at construction
 * instead of silently widening or narrowing access.
 */
public final class RestrictedNetworks {

    private final List<long[]> ranges = new ArrayList<>();
    private final Set<String> exact = new HashSet<>();

    public RestrictedNetworks(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return;
        }
        for (String raw : commaSeparated.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                ranges.add(range(entry.substring(0, slash), entry.substring(slash + 1), entry));
            } else {
                exact.add(canonicalExact(entry));
            }
        }
    }

    public boolean matches(String address) {
        String normalized = normalize(address);
        if (normalized == null) {
            return false;
        }
        Long ipv4 = parseIpv4(normalized);
        if (ipv4 != null) {
            for (long[] range : ranges) {
                long network = range[0];
                long mask = range[1];
                if ((ipv4 & mask) == network) {
                    return true;
                }
            }
        }
        return exact.contains(normalized);
    }

    private static long[] range(String network, String prefix, String original) {
        Long ip = parseIpv4(normalize(network));
        if (ip == null) {
            throw new IllegalArgumentException("invalid admin restricted network: " + original);
        }
        int bits;
        try {
            bits = Integer.parseInt(prefix.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid admin restricted network: " + original, failure);
        }
        if (bits < 0 || bits > 32) {
            throw new IllegalArgumentException("invalid admin restricted network: " + original);
        }
        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return new long[] {ip & mask, mask};
    }

    private static String canonicalExact(String entry) {
        String cleaned = clean(entry);
        if (cleaned != null) {
            Long ipv4 = parseIpv4(cleaned);
            if (ipv4 != null) {
                return ipv4Text(ipv4);
            }
            try {
                return InetAddress.ofLiteral(cleaned).getHostAddress();
            } catch (IllegalArgumentException ignored) {
                // fall through to the failure below
            }
        }
        throw new IllegalArgumentException("invalid admin restricted network: " + entry);
    }

    private static String normalize(String address) {
        String cleaned = clean(address);
        if (cleaned == null) {
            return null;
        }
        Long ipv4 = parseIpv4(cleaned);
        if (ipv4 != null) {
            return ipv4Text(ipv4);
        }
        try {
            return InetAddress.ofLiteral(cleaned).getHostAddress();
        } catch (IllegalArgumentException invalidLiteral) {
            return null;
        }
    }

    private static String clean(String address) {
        if (address == null) {
            return null;
        }
        String value = address.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("::ffff:")) {
            String mapped = value.substring("::ffff:".length());
            if (parseIpv4(mapped) != null) {
                return mapped;
            }
        }
        return value.isEmpty() ? null : value;
    }

    private static String ipv4Text(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    private static Long parseIpv4(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        long result = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return null;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }
}
