package com.aicabinet.trade.payment;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Component
public class WeChatPayV3Signer {

    public String sign(String message, String privateKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(loadPrivateKey(privateKeyPem));
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 sign failed", e);
        }
    }

    public boolean verify(String message, String signatureBase64, String platformCertPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(loadPublicKey(platformCertPem));
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            throw new IllegalStateException("wechat v3 verify failed", e);
        }
    }

    /** 小程序调起支付签名（API v3） */
    public String signJsapi(String appId, String timeStamp, String nonceStr, String packageValue, String privateKeyPem) {
        String message = appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        return sign(message, privateKeyPem);
    }

    private static PrivateKey loadPrivateKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private static PublicKey loadPublicKey(String pem) throws Exception {
        String normalized = pem.contains("BEGIN CERTIFICATE")
                ? pem
                : "-----BEGIN CERTIFICATE-----\n" + pem + "\n-----END CERTIFICATE-----";
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return factory.generateCertificate(
                new java.io.ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8))
        ).getPublicKey();
    }
}
