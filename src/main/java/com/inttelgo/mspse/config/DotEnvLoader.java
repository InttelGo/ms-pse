package com.inttelgo.mspse.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DotEnvLoader {

    private DotEnvLoader() {
    }

    public static void load() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = unquote(trimmed.substring(separator + 1).trim());
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer .env", exception);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char start = value.charAt(0);
            char end = value.charAt(value.length() - 1);
            if ((start == '"' && end == '"') || (start == '\'' && end == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
