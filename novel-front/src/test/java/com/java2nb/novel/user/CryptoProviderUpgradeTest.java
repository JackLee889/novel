package com.java2nb.novel.user;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import static org.junit.jupiter.api.Assertions.*;

class CryptoProviderUpgradeTest {
    @Test void latestProviderCanSignVerifyAndRejectModifiedContent() throws Exception {
        var provider = new BouncyCastleProvider();
        assertEquals("1.86", provider.getVersionStr());
        var generator = KeyPairGenerator.getInstance("EC", provider);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"));
        var keys = generator.generateKeyPair();
        byte[] content = "isolated crypto compatibility check".getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("SM3withSM2", provider);
        signer.initSign(keys.getPrivate()); signer.update(content);
        byte[] signed = signer.sign();
        var verifier = Signature.getInstance("SM3withSM2", provider);
        verifier.initVerify(keys.getPublic()); verifier.update(content);
        assertTrue(verifier.verify(signed));
        verifier.initVerify(keys.getPublic()); verifier.update("changed".getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(signed));
    }
}
