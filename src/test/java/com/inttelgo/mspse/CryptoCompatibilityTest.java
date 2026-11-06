package com.inttelgo.mspse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.exception.BadRequestException;
import com.inttelgo.mspse.service.IdCipher;
import com.inttelgo.mspse.service.PseCrypto;
import org.junit.jupiter.api.Test;

/** Compatibilidad con el SDK PHP de PSE y con encryptId/decryptId del backend Node. */
class CryptoCompatibilityTest {

    static final String KEY = "TESTKEY0123456789ABCDEFGHIJKLMNO";
    static final String IV = "TESTIV0123456789";
    static final String PLAINTEXT = "{\"entityCode\":\"901132952\",\"valor\":\"ñandú\"}";

    /** Generado con {@code JWEServices::processEncrypt} del PHP original, con KEY e IV de prueba. */
    static final String PHP_JWE = "eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..RBBqWvjfLcglfTvm."
            + "9ANBOH2phk3gA4xWEi4P82ItxnycPwfdOsQoSh0qN7M8E2EasbP-tqir65fpSWCsNVRvC7fxLPKsU2geKHRTHlq3ggu7bqtYUV2TnW9Z8_y2jpYKCpvqp3eJGLoPtBJnpMQT5r19A_k"
            + ".QEoV7L2EWB9LSrmqZgqSsQ";

    /** Generado con {@code encryptId('1700000000123')} del Node con TOKEN_SECRET=test-secret. */
    static final String NODE_HASH = "e51b7159e2db2fa80af0ec005b5d22cf:007249958ad490a63c9d5c4be0e2e2ab";

    static AppProperties properties(String hashSecret) {
        return new AppProperties("America/Bogota", "", hashSecret, null, null,
                new AppProperties.Pse(null, null, "v2", null, null, null, KEY, IV, null, null, null, null, null, null, null, 30, 80),
                null, null);
    }

    @Test
    void decryptsJweFromPhpSdk() {
        assertEquals(PLAINTEXT, new PseCrypto(properties(null)).processDecrypt(PHP_JWE));
    }

    @Test
    void decryptsJweSentAsJsonString() {
        assertEquals(PLAINTEXT, new PseCrypto(properties(null)).processDecrypt("\"" + PHP_JWE + "\""));
    }

    @Test
    void encryptsInTheSameFormat() {
        PseCrypto crypto = new PseCrypto(properties(null));
        String jwe = crypto.processEncrypt(PLAINTEXT);
        assertTrue(jwe.startsWith("eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0.."), jwe);
        assertEquals(5, jwe.split("\\.", -1).length);
        assertEquals(PLAINTEXT, crypto.processDecrypt(jwe));
        assertNotEquals(jwe, crypto.processEncrypt(PLAINTEXT));
    }

    @Test
    void rejectsTamperedJwe() {
        PseCrypto crypto = new PseCrypto(properties(null));
        String tampered = PHP_JWE.substring(0, PHP_JWE.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> crypto.processDecrypt(tampered));
    }

    @Test
    void readsNodeHashesAndWritesCompatibleOnes() {
        IdCipher cipher = new IdCipher(properties("test-secret"));
        assertEquals("1700000000123", cipher.decrypt(NODE_HASH));
        String hash = cipher.encrypt("42");
        assertTrue(hash.matches("[0-9a-f]{32}:[0-9a-f]+"), hash);
        assertEquals(42L, cipher.decryptId(hash));
    }

    @Test
    void invalidHashIsBadRequest() {
        IdCipher cipher = new IdCipher(properties("test-secret"));
        assertThrows(BadRequestException.class, () -> cipher.decrypt("nope"));
        assertThrows(BadRequestException.class, () -> cipher.decrypt("00:zz"));
        assertThrows(BadRequestException.class, () -> new IdCipher(properties("otro")).decrypt(NODE_HASH));
    }
}
