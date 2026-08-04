package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeChatPlatformCertificateStoreTest {

    private static final String API_V3_KEY = "01234567890123456789012345678901";

    @Mock
    private WeChatPayV3Client v3Client;

    private WeChatPayV3Aead aead;
    private ObjectMapper objectMapper;
    private WeChatPayTestKeys.Material keys;

    @BeforeEach
    void setUp() {
        aead = new WeChatPayV3Aead();
        objectMapper = new ObjectMapper();
        keys = WeChatPayTestKeys.generate();
    }

    @Test
    void resolveCertificatePem_prefersStaticConfig() {
        WeChatPayProperties props = properties(true, keys.platformCertPem(), false);
        WeChatPlatformCertificateStore store = new WeChatPlatformCertificateStore(props, v3Client, aead);

        Optional<String> pem = store.resolveCertificatePem("ANY-SERIAL");
        assertTrue(pem.isPresent());
        assertEquals(keys.platformCertPem(), pem.get());
        verifyNoInteractions(v3Client);
    }

    @Test
    void refresh_loadsEncryptedCertificatesFromApi() throws Exception {
        WeChatPayProperties props = properties(true, "", true);
        WeChatPlatformCertificateStore store = new WeChatPlatformCertificateStore(props, v3Client, aead);

        String serial = "TESTSERIAL123456";
        when(v3Client.get("/v3/certificates")).thenReturn(certificatesResponse(serial, keys.platformCertPem()));

        store.refresh();

        Optional<String> pem = store.resolveCertificatePem(serial);
        assertTrue(pem.isPresent());
        assertTrue(pem.get().contains("BEGIN CERTIFICATE"));
    }

    private WeChatPayProperties properties(boolean enabled, String platformCert, boolean autoFetch) {
        return new WeChatPayProperties(
                enabled,
                "wx-test-app",
                "1900000109",
                "https://example.com/notify",
                API_V3_KEY,
                "MERCHANT_SERIAL",
                keys.merchantPrivateKeyPem(),
                platformCert,
                autoFetch
        );
    }

    private JsonNode certificatesResponse(String serial, String certPem) throws Exception {
        String nonce = "cert-nonce-12";
        String ciphertext = encryptCertificate(certPem, nonce);

        ObjectNode encrypted = objectMapper.createObjectNode();
        encrypted.put("algorithm", "AEAD_AES_256_GCM");
        encrypted.put("nonce", nonce);
        encrypted.put("associated_data", "certificate");
        encrypted.put("ciphertext", ciphertext);

        ObjectNode item = objectMapper.createObjectNode();
        item.put("serial_no", serial);
        item.put("effective_time", "2024-01-01T00:00:00+08:00");
        item.put("expire_time", "2029-01-01T00:00:00+08:00");
        item.set("encrypt_certificate", encrypted);

        ArrayNode data = objectMapper.createArrayNode();
        data.add(item);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("data", data);
        return root;
    }

    private static String encryptCertificate(String certPem, String nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        cipher.updateAAD("certificate".getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher.doFinal(certPem.getBytes(StandardCharsets.UTF_8)));
    }
}
