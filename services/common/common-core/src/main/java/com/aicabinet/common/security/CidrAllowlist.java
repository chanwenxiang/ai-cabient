package com.aicabinet.common.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.OptionalInt;

/**
 * 简易 IPv4 CIDR 匹配（内部 API 来源限制）。
 * 将 loopback IPv6 映射为 127.0.0.1；非 IPv4 在启用白名单时拒绝。
 */
public final class CidrAllowlist {

    private CidrAllowlist() {}

    public static boolean isAllowed(String remoteAddr, List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        OptionalInt ip = toIpv4Int(normalize(remoteAddr));
        if (ip.isEmpty()) {
            return false;
        }
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            if (matchesCidr(ip.getAsInt(), cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String addr) {
        if (addr == null || addr.isBlank()) {
            return "";
        }
        String a = addr.trim();
        if ("::1".equals(a) || "0:0:0:0:0:0:0:1".equalsIgnoreCase(a)) {
            return "127.0.0.1";
        }
        if (a.startsWith("/")) {
            a = a.substring(1);
        }
        int pct = a.indexOf('%');
        if (pct > 0) {
            a = a.substring(0, pct);
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon && a.contains(".")) {
            a = a.substring(0, colon);
        }
        return a;
    }

    private static boolean matchesCidr(int ip, String cidr) {
        String network;
        int prefix;
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            network = cidr;
            prefix = 32;
        } else {
            network = cidr.substring(0, slash).trim();
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        OptionalInt net = toIpv4Int(network);
        if (net.isEmpty()) {
            return false;
        }
        if (prefix == 0) {
            return true;
        }
        int mask = prefix == 32 ? 0xFFFFFFFF : (~0 << (32 - prefix));
        return (ip & mask) == (net.getAsInt() & mask);
    }

    private static OptionalInt toIpv4Int(String host) {
        if (host == null || host.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length != 4) {
                return OptionalInt.empty();
            }
            int value = ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
            return OptionalInt.of(value);
        } catch (UnknownHostException e) {
            return OptionalInt.empty();
        }
    }
}
