package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@ConfigurationProperties(prefix = "aicabinet.qr")
public record QrProperties(
        String publicHost,
        String consumerH5Base,
        String wechatMpAppId,
        String wechatMpPath,
        /** release / trial / develop，仅 URL Link 使用 */
        String wechatMpEnv
) {
    public String normalizedPublicHost() {
        String host = publicHost == null ? "" : publicHost.trim();
        if (host.isEmpty() || "auto".equalsIgnoreCase(host)) {
            host = detectLanHttpBase();
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    public String normalizedConsumerH5Base() {
        String base = consumerH5Base == null ? "" : consumerH5Base.trim();
        if (base.isEmpty() || "auto".equalsIgnoreCase(base)) {
            // 与柜机码同主机，默认消费者 H5 开发端口 3002
            String pub = normalizedPublicHost();
            try {
                java.net.URI u = java.net.URI.create(pub.contains("://") ? pub : "http://" + pub);
                String scheme = u.getScheme() != null ? u.getScheme() : "http";
                String h = u.getHost() != null ? u.getHost() : "127.0.0.1";
                base = scheme + "://" + h + ":3002/";
            } catch (Exception e) {
                base = "http://127.0.0.1:3002/";
            }
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base;
    }

    public String wechatPath() {
        String path = wechatMpPath == null || wechatMpPath.isBlank()
                ? "pages/index/index"
                : wechatMpPath.trim();
        return path.startsWith("/") ? path.substring(1) : path;
    }

    public String wechatEnvVersion() {
        String env = wechatMpEnv == null || wechatMpEnv.isBlank() ? "release" : wechatMpEnv.trim();
        return switch (env.toLowerCase()) {
            case "trial", "develop", "release" -> env.toLowerCase();
            default -> "release";
        };
    }

    /** 优先非回环 IPv4，便于手机同网扫码；失败则 localhost。 */
    static String detectLanHttpBase() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return "http://" + addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            /* fall through */
        }
        return "http://localhost";
    }
}
