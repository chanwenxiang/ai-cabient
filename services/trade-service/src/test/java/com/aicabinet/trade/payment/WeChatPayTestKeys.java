package com.aicabinet.trade.payment;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/** 测试用 RSA 密钥与自签证书生成（仅 test scope）。 */
public final class WeChatPayTestKeys {

    public record Material(String merchantPrivateKeyPem, String platformPrivateKeyPem, String platformCertPem) {}

    public static Material generate() {
        try {
            KeyPair merchant = rsa2048();
            KeyPair platform = rsa2048();
            String merchantPem = toPkcs8Pem(merchant.getPrivate());
            String platformPem = toPkcs8Pem(platform.getPrivate());
            String platformCertPem = selfSignedCertPem(platform);
            return new Material(merchantPem, platformPem, platformCertPem);
        } catch (Exception e) {
            throw new IllegalStateException("generate wechat test keys failed", e);
        }
    }

    private static KeyPair rsa2048() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String toPkcs8Pem(PrivateKey privateKey) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }

    private static String selfSignedCertPem(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date start = new Date(now);
        Date end = new Date(now + 365L * 24 * 60 * 60 * 1000);
        X500Name subject = new X500Name("CN=WeChat Pay Test Platform");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                start,
                end,
                subject,
                keyPair.getPublic()
        );
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate()))
        );
        StringWriter writer = new StringWriter();
        writer.write("-----BEGIN CERTIFICATE-----\n");
        writer.write(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(cert.getEncoded()));
        writer.write("\n-----END CERTIFICATE-----\n");
        return writer.toString();
    }

    private WeChatPayTestKeys() {}
}
