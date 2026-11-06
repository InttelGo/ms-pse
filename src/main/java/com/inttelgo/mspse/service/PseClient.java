package com.inttelgo.mspse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.config.HttpClients;
import com.inttelgo.mspse.exception.ApiException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Llamadas a la API de PSE (lo que hacía {@code pse-microservice.php}). El token OAuth se pide con
 * client credentials y se guarda en memoria hasta poco antes de vencer; si PSE responde 401 se pide
 * otro y se reintenta una vez.
 */
@Service
public class PseClient {

    private static final Logger log = LoggerFactory.getLogger(PseClient.class);
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(60);
    private static final long DEFAULT_TOKEN_SECONDS = 3600;

    public static final String BANKS = "GetBankListNF";
    public static final String CREATE = "CreateTransactionPaymentNF";
    public static final String INFO = "GetTransactionInformationNF";
    public static final String FINALIZE = "FinalizeTransactionPaymentNF";

    private final AppProperties properties;
    private final PseCrypto crypto;
    private final ObjectMapper mapper;
    private final RestClient client;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public PseClient(AppProperties properties, PseCrypto crypto, ObjectMapper mapper, RestClient.Builder builder) {
        this.properties = properties;
        this.crypto = crypto;
        this.mapper = mapper;
        this.client = HttpClients.build(builder, null, Duration.ofSeconds(Math.max(properties.pse().timeoutSeconds(), 5)));
    }

    public JsonNode banks() {
        return call(BANKS, Map.of("entityCode", properties.pse().entityCode()));
    }

    public JsonNode createTransaction(Map<String, Object> request) {
        return call(CREATE, request);
    }

    public JsonNode transactionInfo(String trazabilityCode) {
        return call(INFO, infoRequest(trazabilityCode));
    }

    public JsonNode finalizeTransaction(String trazabilityCode) {
        return call(FINALIZE, infoRequest(trazabilityCode));
    }

    private Map<String, Object> infoRequest(String trazabilityCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("trazabilityCode", trazabilityCode);
        request.put("entityCode", properties.pse().entityCode());
        return request;
    }

    private JsonNode call(String function, Object request) {
        String body = crypto.processEncrypt(json(request));
        try {
            return post(function, body, token());
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().value() != 401) {
                throw httpError(function, exception);
            }
            log.info("PSE respondió 401 en {}: se renueva el token", function);
            invalidateToken();
            try {
                return post(function, body, token());
            } catch (HttpStatusCodeException retry) {
                throw httpError(function, retry);
            } catch (RestClientException retry) {
                throw networkError(function, retry);
            }
        } catch (RestClientException exception) {
            throw networkError(function, exception);
        }
    }

    private JsonNode post(String function, String body, String bearer) {
        AppProperties.Pse pse = properties.pse();
        String url = HttpClients.trimSlash(required(pse.url(), "PSE_URL")) + "/" + pse.version()
                + "/psewebapinf/api/" + function + "?apikey=" + required(pse.apiKey(), "PSE_API_KEY");
        String response = client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .body(body)
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PSE_EMPTY_RESPONSE", "PSE respondió vacío en " + function);
        }
        String plain;
        try {
            plain = crypto.processDecrypt(response);
        } catch (RuntimeException exception) {
            log.error("No se pudo descifrar la respuesta de PSE en {}: {}", function, exception.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PSE_DECRYPT_ERROR", "No se pudo leer la respuesta de PSE");
        }
        try {
            return mapper.readTree(plain);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PSE_PARSE_ERROR", "Respuesta de PSE inválida");
        }
    }

    synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        AppProperties.Pse pse = properties.pse();
        String credentials = Base64.getEncoder().encodeToString(
                (required(pse.clientId(), "PSE_CLIENT_ID") + ":" + required(pse.clientSecret(), "PSE_CLIENT_SECRET"))
                        .getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode response;
        try {
            response = client.post()
                    .uri(HttpClients.trimSlash(required(pse.url(), "PSE_URL")) + required(pse.authPath(), "PSE_AUTH_PATH"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException exception) {
            int status = exception.getStatusCode().value();
            log.error("PSE rechazó el token: HTTP {}", status);
            throw status == 401 || status == 403
                    ? new ApiException(HttpStatus.BAD_GATEWAY, "PSE_OAUTH_UNAUTHORIZED",
                            "No se pudo obtener el token de PSE: credenciales inválidas o acceso denegado.")
                    : new ApiException(HttpStatus.BAD_GATEWAY, "PSE_OAUTH_HTTP_" + status,
                            "Error al obtener token de PSE (respuesta HTTP " + status + ").");
        } catch (RestClientException exception) {
            throw networkError("token", exception);
        }
        String accessToken = response == null ? null : response.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PSE_TOKEN_ERROR", "Error al generar token de PSE");
        }
        long seconds = response.path("expires_in").asLong(DEFAULT_TOKEN_SECONDS);
        token = accessToken;
        tokenExpiresAt = Instant.now().plusSeconds(seconds).minus(TOKEN_MARGIN);
        return token;
    }

    synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException httpError(String function, HttpStatusCodeException exception) {
        log.error("PSE {} respondió HTTP {}: {}", function, exception.getStatusCode().value(),
                exception.getResponseBodyAsString());
        if (exception.getStatusCode().value() == 401) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "PSE_TOKEN_EXPIRED", "PSE rechazó el token de acceso");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "PSE_ERROR",
                "Error en el servidor PSE (HTTP " + exception.getStatusCode().value() + ")");
    }

    /** Equivalente a {@code mapPseAxiosNetworkError}: errores de red con un código claro. */
    static ApiException networkError(String function, RestClientException exception) {
        Throwable cause = rootCause(exception);
        log.error("Error de red con PSE en {}: {}", function, cause.toString());
        if (exception instanceof ResourceAccessException) {
            if (cause instanceof HttpConnectTimeoutException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PSE_CONNECTION_TIMEOUT",
                        "No hubo respuesta del servicio PSE (tiempo de conexión agotado). Verifique la red o intente más tarde.");
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PSE_REQUEST_TIMEOUT",
                        "La solicitud al servicio PSE excedió el tiempo de espera. Intente de nuevo más tarde.");
            }
            if (cause instanceof UnknownHostException) {
                return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSE_DNS_ERROR",
                        "No se pudo resolver el host del servicio PSE.");
            }
            if (cause instanceof ConnectException) {
                return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSE_CONNECTION_REFUSED",
                        "No se pudo establecer conexión con el servicio PSE.");
            }
            if (cause instanceof SSLException) {
                return new ApiException(HttpStatus.BAD_GATEWAY, "PSE_TLS_ERROR",
                        "Error de certificado o canal seguro al conectar con PSE.");
            }
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "PSE_CONNECTION_ERROR",
                "La conexión con PSE se interrumpió. Intente de nuevo.");
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " no está configurada");
        }
        return value;
    }
}
