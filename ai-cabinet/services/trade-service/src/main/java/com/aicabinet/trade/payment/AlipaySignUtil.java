package com.aicabinet.trade.payment;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class AlipaySignUtil {

    public String signRsa2(Map<String, String> params, String privateKeyPem) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("sign");
        String content = sorted.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(loadPrivateKey(privateKeyPem));
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("alipay RSA2 sign failed", e);
        }
    }

    private static PrivateKey loadPrivateKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }
}
