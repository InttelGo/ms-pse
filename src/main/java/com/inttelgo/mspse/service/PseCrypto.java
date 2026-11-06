package com.inttelgo.mspse.service;

import com.inttelgo.mspse.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Cifrado de los mensajes PSE, igual que {@code JWEServices.php} del SDK:
 * <ol>
 *   <li>AES-256-GCM con la llave y el IV de PSE; el resultado es {@code base64(iv + cifrado + tag)}.</li>
 *   <li>Ese texto va como payload de un JWE compacto {@code alg=dir, enc=A256GCM} con la misma llave.</li>
 * </ol>
 * Para leer la respuesta se hace lo inverso.
 */
@Component
public class PseCrypto {

    private static final String HEADER = "{\"alg\":\"dir\",\"enc\":\"A256GCM\"}";
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int PSE_IV_BYTES = 16;
    private static final int JWE_IV_BYTES = 12;

    private final SecureRandom random = new SecureRandom();
    private final AppProperties properties;

    public PseCrypto(AppProperties properties) {
        this.properties = properties;
    }

    public String processEncrypt(String message) {
        return toJwe(encrypt(message, key(), iv()), key());
    }

    public String processDecrypt(String jwe) {
        return decrypt(fromJwe(jwe, key()), key());
    }

    static String encrypt(String plaintext, byte[] key, byte[] iv) {
        byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key, iv, null, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    static String decrypt(String encoded, byte[] key) {
        byte[] combined = Base64.getDecoder().decode(encoded.trim());
        if (combined.length < PSE_IV_BYTES + TAG_BYTES) {
            throw new IllegalArgumentException("Mensaje PSE cifrado incompleto");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, PSE_IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(combined, PSE_IV_BYTES, combined.length);
        return new String(gcm(Cipher.DECRYPT_MODE, key, iv, null, sealed), StandardCharsets.UTF_8);
    }

    String toJwe(String payload, byte[] key) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[JWE_IV_BYTES];
        random.nextBytes(iv);
        byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key, iv, header.getBytes(StandardCharsets.US_ASCII),
                payload.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - TAG_BYTES);
        byte[] tag = Arrays.copyOfRange(sealed, sealed.length - TAG_BYTES, sealed.length);
        return header + ".." + url.encodeToString(iv) + "." + url.encodeToString(ciphertext) + "." + url.encodeToString(tag);
    }

    static String fromJwe(String compact, byte[] key) {
        String jwe = unquote(compact);
        String[] parts = jwe.split("\\.", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Respuesta PSE no es un JWE compacto");
        }
        Base64.Decoder url = Base64.getUrlDecoder();
        String header = new String(url.decode(parts[0]), StandardCharsets.UTF_8).replace(" ", "");
        if (!header.contains("\"alg\":\"dir\"") || !header.contains("\"enc\":\"A256GCM\"")) {
            throw new IllegalArgumentException("Algoritmo JWE no soportado: " + header);
        }
        byte[] iv = url.decode(parts[2]);
        byte[] ciphertext = url.decode(parts[3]);
        byte[] tag = url.decode(parts[4]);
        byte[] sealed = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, sealed, 0, ciphertext.length);
        System.arraycopy(tag, 0, sealed, ciphertext.length, tag.length);
        return new String(gcm(Cipher.DECRYPT_MODE, key, iv, parts[0].getBytes(StandardCharsets.US_ASCII), sealed),
                StandardCharsets.UTF_8);
    }

    /** PSE a veces responde el JWE como string JSON entre comillas. */
    private static String unquote(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Error de cifrado PSE: " + exception.getMessage(), exception);
        }
    }

    private byte[] key() {
        byte[] key = bytes(properties.pse().encryptKey(), "PSE_ENCRYPT_KEY");
        if (key.length != 32) {
            throw new IllegalStateException("PSE_ENCRYPT_KEY debe tener 32 caracteres");
        }
        return key;
    }

    private byte[] iv() {
        byte[] iv = bytes(properties.pse().encryptIv(), "PSE_ENCRYPT_IV");
        if (iv.length != PSE_IV_BYTES) {
            throw new IllegalStateException("PSE_ENCRYPT_IV debe tener 16 caracteres");
        }
        return iv;
    }

    private static byte[] bytes(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " no está configurada");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
