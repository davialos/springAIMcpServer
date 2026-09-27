package com.springaimcpservercommon.security.apikey;

import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 network in CIDR notation ({@code 10.0.0.0/8}, {@code 2001:db8::/32}; a bare address is a /32 or
 * /128). Only IP literals are accepted — parsing never triggers a DNS lookup.
 */
public final class CidrBlock {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final byte[] network;
    private final int prefixLength;

    private CidrBlock(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses CIDR text.
     *
     * @param cidr text
     * @return the block
     * @throws IllegalArgumentException if not an IP literal network
     */
    public static CidrBlock parse(String cidr) {
        Objects.requireNonNull(cidr, "cidr");
        String text = cidr.trim();
        int slash = text.indexOf('/');
        String address = slash < 0 ? text : text.substring(0, slash);
        InetAddress inet = literal(address);
        if (inet == null) {
            throw new IllegalArgumentException("not an IP literal network");
        }
        byte[] bytes = inet.getAddress();
        int max = bytes.length * 8;
        int prefix = max;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix length", e);
            }
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("invalid prefix length");
            }
        }
        return new CidrBlock(mask(bytes, prefix), prefix);
    }

    /**
     * Parses an IP literal without DNS.
     *
     * @param text address text
     * @return the address, or {@code null} if it is not an IPv4/IPv6 literal
     */
    public static @Nullable InetAddress literal(@Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > 45) {
            return null;
        }
        boolean v4 = IPV4.matcher(text).matches();
        boolean v6 = !v4 && text.indexOf(':') >= 0 && IPV6.matcher(text).matches();
        if (!v4 && !v6) {
            return null;
        }
        if (v4) {
            for (String part : text.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(text); // literal only: validated above, so no resolution happens
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Whether an address lies in this network. IPv4 and IPv6 never match each other (no mapped-address folding).
     *
     * @param address address
     * @return {@code true} if contained
     */
    public boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != network.length) {
            return false;
        }
        byte[] masked = mask(bytes, prefixLength);
        return java.util.Arrays.equals(masked, network);
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int bits = Math.max(0, Math.min(8, prefix - i * 8));
            int m = bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF;
            out[i] = (byte) (out[i] & m);
        }
        return out;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            return "cidr/" + prefixLength;
        }
    }
}
