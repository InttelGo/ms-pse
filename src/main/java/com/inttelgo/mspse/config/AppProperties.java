package com.inttelgo.mspse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Variables de entorno del microservicio (ver application.yml y .env.example). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String zone,
        String landingUrl,
        String hashSecret,
        Build build,
        Remote service,
        Pse pse,
        Pending pending,
        Recaptcha recaptcha) {

    /**
     * Factura y pagos que se crean en ms-build cuando una transacción se aprueba. {@code systemUserId} es el
     * usuario "Mi Recaudo PSE": va en {@code create_by} de la transacción y en el X-User-Id hacia ms-build.
     */
    public record Build(
            String url,
            Long systemUserId,
            Long methodId,
            Long officeId,
            Long statusId,
            boolean electronic) {
    }

    public record Remote(String url) {
    }

    public record Pse(
            String url,
            String authPath,
            String version,
            String apiKey,
            String clientId,
            String clientSecret,
            String encryptKey,
            String encryptIv,
            String entityCode,
            String serviceCode,
            String entityUrl,
            String beneficiaryType,
            String beneficiaryIdentify,
            String beneficiaryName,
            String beneficiaryCategory,
            int timeoutSeconds,
            int descriptionMaxLength) {
    }

    public record Pending(boolean enabled, String cron, int maxHours) {
    }

    public record Recaptcha(boolean enabled, String secret, String verifyUrl) {
    }
}
