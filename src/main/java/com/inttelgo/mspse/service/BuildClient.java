package com.inttelgo.mspse.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.config.HttpClients;
import com.inttelgo.mspse.config.RequestHeaders;
import com.inttelgo.mspse.exception.ApiException;
import com.inttelgo.mspse.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Facturas, cobros y pagos en ms-build. Las escrituras van con el usuario de sistema de PSE. */
@Service
public class BuildClient {

    private final RestClient client;
    private final AppProperties.Build config;
    private final ObjectMapper mapper;

    public BuildClient(RestClient.Builder builder, AppProperties properties, ObjectMapper mapper) {
        this.config = properties.build();
        this.mapper = mapper;
        this.client = HttpClients.build(builder, config.url(), Duration.ofSeconds(30));
    }

    public record BuildLine(
            @JsonProperty("service_id") Long serviceId,
            @JsonProperty("extra_id") Long extraId,
            String description,
            @JsonProperty("internet_value") BigDecimal internetValue,
            @JsonProperty("telephony_value") BigDecimal telephonyValue,
            @JsonProperty("television_value") BigDecimal televisionValue,
            BigDecimal value,
            BigDecimal discount) {
    }

    /** Cuerpo de {@code POST /api/v1/build/client/{clientId}} sin caja: el pago no entra a caja física. */
    public record BuildRequest(
            @JsonProperty("build_at") LocalDate buildAt,
            @JsonProperty("method_id") Long methodId,
            @JsonProperty("office_id") Long officeId,
            @JsonProperty("status_id") Long statusId,
            /** Porcentaje 1-100; ms-build lo aplica a cada línea y redondea hacia arriba a la centena. */
            BigDecimal discount,
            String observation,
            @JsonProperty("electronic_build") Boolean electronicBuild,
            List<BuildLine> details) {
    }

    record PaymentRequest(
            @JsonProperty("quota_number") Integer quotaNumber,
            BigDecimal value,
            String description,
            @JsonProperty("method_id") Long methodId) {
    }

    public record Collection(Long id, Long statusId, int quotas, int paidQuotas, Integer lastQuota, Long clientId) {

        public int nextQuota() {
            return lastQuota == null ? paidQuotas + 1 : lastQuota + 1;
        }
    }

    public Long createBuild(Long clientId, BuildRequest request) {
        JsonNode data = send(() -> client.post()
                .uri("/api/v1/build/client/{clientId}", clientId)
                .header(RequestHeaders.USER_ID, String.valueOf(config.systemUserId()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class), "crear la factura");
        JsonNode id = data.path("build").path("id");
        if (!id.canConvertToLong()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_ERROR", "ms-build no devolvió el id de la factura");
        }
        return id.asLong();
    }

    /** Estados de servicio ({@code relation}) de una categoría de movimiento de ms-build. */
    public List<Long> categoryStatuses(long categoryId) {
        JsonNode data = send(() -> client.get()
                .uri("/api/v1/build/movement-categories/{id}", categoryId)
                .retrieve()
                .body(JsonNode.class), "consultar la categoría " + categoryId);
        List<Long> statuses = new java.util.ArrayList<>();
        for (JsonNode status : data.path("relation")) {
            statuses.add(status.asLong());
        }
        return statuses;
    }

    public Collection collection(Long id) {
        JsonNode data;
        try {
            data = send(() -> client.get()
                    .uri("/api/v1/build/collections/{id}", id)
                    .retrieve()
                    .body(JsonNode.class), "consultar el cobro " + id);
        } catch (NotFoundException exception) {
            throw new NotFoundException("El cobro " + id + " no existe");
        }
        JsonNode last = data.path("last_payment");
        return new Collection(
                data.path("id").asLong(id),
                data.hasNonNull("status_id") ? data.get("status_id").asLong() : null,
                data.path("quotas").asInt(0),
                data.path("paid_quotas").asInt(0),
                last.hasNonNull("quota_number") ? last.get("quota_number").asInt() : null,
                data.hasNonNull("client_by") ? data.get("client_by").asLong() : null);
    }

    public Long createPayment(Long collectionId, int quota, BigDecimal value, String description) {
        PaymentRequest request = new PaymentRequest(quota, value, description, config.methodId());
        JsonNode data = send(() -> client.post()
                .uri("/api/v1/build/collections/{collectionId}/payments", collectionId)
                .header(RequestHeaders.USER_ID, String.valueOf(config.systemUserId()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class), "registrar el pago del cobro " + collectionId);
        JsonNode id = data.path("id");
        if (!id.canConvertToLong()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_ERROR", "ms-build no devolvió el id del pago");
        }
        return id.asLong();
    }

    private JsonNode send(java.util.function.Supplier<JsonNode> call, String action) {
        JsonNode body;
        try {
            body = call.get();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new NotFoundException("ms-build: " + remoteMessage(exception, "no encontrado"));
        } catch (HttpStatusCodeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_ERROR",
                    "ms-build no pudo " + action + ": " + remoteMessage(exception, "HTTP " + exception.getStatusCode().value()));
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_UNAVAILABLE", "No se pudo " + action + " en ms-build");
        }
        if (body == null || !body.path("success").asBoolean(false)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_ERROR", "ms-build no pudo " + action);
        }
        return body.path("data");
    }

    private String remoteMessage(HttpStatusCodeException exception, String fallback) {
        try {
            JsonNode body = mapper.readTree(exception.getResponseBodyAsString());
            String message = body.path("error").path("message").asText("");
            return message.isBlank() ? fallback : message;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
