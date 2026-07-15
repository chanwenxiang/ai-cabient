package com.aicabinet.trade.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeChatPayV3AeadTest {

    private final WeChatPayV3Aead aead = new WeChatPayV3Aead();

    @Test
    void decrypt_roundTrip() throws Exception {
        String apiV3Key = "01234567890123456789012345678901";
        String associatedData = "transaction";
        String nonce = "abcdef123456";
        String plain = "{\"out_trade_no\":\"RTEST001\",\"trade_state\":\"SUCCESS\"}";

        byte[] cipherBytes = encrypt(apiV3Key, associatedData, nonce, plain);
        String ciphertext = Base64.getEncoder().encodeToString(cipherBytes);

        String decrypted = aead.decrypt(apiV3Key, associatedData, nonce, ciphertext);
        assertEquals(plain, decrypted);
    }

    private static byte[] encrypt(String apiV3Key, String associatedData, String nonce, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }
}
