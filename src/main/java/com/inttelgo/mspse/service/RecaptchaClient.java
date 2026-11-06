package com.inttelgo.mspse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.config.HttpClients;
import com.inttelgo.mspse.exception.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Valida el reCAPTCHA del landing antes de iniciar un pago. */
@Service
public class RecaptchaClient {

    private static final Logger log = LoggerFactory.getLogger(RecaptchaClient.class);

    private final AppProperties.Recaptcha config;
    private final RestClient client;

    public RecaptchaClient(AppProperties properties, RestClient.Builder builder) {
        this.config = properties.recaptcha();
        this.client = HttpClients.build(builder, null, Duration.ofSeconds(10));
    }

    public void verify(String token) {
        if (!config.enabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        if (config.secret() == null || config.secret().isBlank()) {
            throw new IllegalStateException("RECAPTCHA_SECRET_KEY no está configurada");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", config.secret());
        form.add("response", token);
        JsonNode response;
        try {
            response = client.post()
                    .uri(config.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            log.error("No se pudo validar reCAPTCHA: {}", exception.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RECAPTCHA_UNAVAILABLE", "No se pudo validar el reCAPTCHA");
        }
        if (response == null || !response.path("success").asBoolean(false)) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_RECAPTCHA", "reCAPTCHA inválido o expirado");
    }
}
