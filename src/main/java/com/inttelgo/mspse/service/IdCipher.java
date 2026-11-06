package com.inttelgo.mspse.service;

import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Cifra el ticket y el id de URL que viajan en los enlaces del landing. Mismo formato que
 * {@code encryptId}/{@code decryptId} del backend Node: AES-256-CBC, llave = SHA-256 del secreto,
 * salida {@code hex(iv):hex(cifrado)}. Así siguen sirviendo los enlaces ya enviados.
 */
@Component
public class IdCipher {

    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();
    private final AppProperties properties;

    public IdCipher(AppProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String value) {
        byte[] iv = new byte[16];
        random.nextBytes(iv);
        byte[] encrypted = cbc(Cipher.ENCRYPT_MODE, iv, value.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(iv) + ":" + HEX.formatHex(encrypted);
    }

    public String decrypt(String hash) {
        String[] parts = hash == null ? new String[0] : hash.trim().split(":");
        if (parts.length != 2) {
            throw new BadRequestException("Enlace inválido");
        }
        key(); // un secreto sin configurar es error del servidor, no del enlace
        try {
            byte[] iv = HEX.parseHex(parts[0]);
            byte[] data = HEX.parseHex(parts[1]);
            if (iv.length != 16) {
                throw new BadRequestException("Enlace inválido");
            }
            return new String(cbc(Cipher.DECRYPT_MODE, iv, data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new BadRequestException("Enlace inválido");
        }
    }

    public Long decryptId(String hash) {
        try {
            return Long.valueOf(decrypt(hash).trim());
        } catch (NumberFormatException exception) {
            throw new BadRequestException("Enlace inválido");
        }
    }

    private byte[] cbc(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key(), "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo procesar el enlace", exception);
        }
    }

    private byte[] key() {
        String secret = properties.hashSecret();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("PSE_HASH_SECRET no está configurada");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
