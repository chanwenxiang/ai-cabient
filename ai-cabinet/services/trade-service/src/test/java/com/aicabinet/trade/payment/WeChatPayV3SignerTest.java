package com.aicabinet.trade.payment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeChatPayV3SignerTest {

    private static WeChatPayTestKeys.Material keys;
    private final WeChatPayV3Signer signer = new WeChatPayV3Signer();

    @BeforeAll
    static void initKeys() {
        keys = WeChatPayTestKeys.generate();
    }

    @Test
    void signAndVerify_requestAuthMessage() {
        String message = "GET\n/v3/certificates\n1700000000\nabc123\n\n";
        String signature = signer.sign(message, keys.merchantPrivateKeyPem());
        assertFalse(signature.isBlank());

        assertFalse(signer.verify(message, signature, keys.platformCertPem()));

        String platformSignature = signer.sign(message, keys.platformPrivateKeyPem());
        assertTrue(signer.verify(message, platformSignature, keys.platformCertPem()));
    }

    @Test
    void signJsapi_producesStableFormat() {
        String paySign = signer.signJsapi(
                "wx1234567890",
                "1700000001",
                "nonce-abc",
                "prepay_id=wx2024test",
                keys.merchantPrivateKeyPem()
        );
        assertNotNull(paySign);
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(paySign));
    }
}
