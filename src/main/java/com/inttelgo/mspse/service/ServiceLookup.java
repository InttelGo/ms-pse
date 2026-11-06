package com.inttelgo.mspse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.config.HttpClients;
import com.inttelgo.mspse.exception.ApiException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Consulta en ms-service el cliente (la factura se crea a ese cliente) y el estado de cada servicio. */
@Service
public class ServiceLookup {

    private final RestClient client;

    public ServiceLookup(RestClient.Builder builder, AppProperties properties) {
        this.client = HttpClients.build(builder, properties.service().url(), Duration.ofSeconds(15));
    }

    /** Cliente dueño y estado actual del servicio en ms-service. */
    public record ServiceInfo(Long clientId, Long statusId) {
    }

    /** Id de servicio → cliente y estado. Los servicios que no existen no vienen en el mapa. */
    public Map<Long, ServiceInfo> byIds(Collection<Long> serviceIds) {
        List<Long> ids = serviceIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        JsonNode body;
        try {
            body = client.post()
                    .uri(uri -> uri.path("/api/v1/service/search")
                            .queryParam("skip", 0)
                            .queryParam("limit", 1000)
                            .queryParam("sort_by", "id")
                            .queryParam("sort_order", "asc")
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filters", List.of(Map.of("column", "id", "operator", "in", "value", ids))))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw unavailable();
        }
        if (body == null || !body.path("success").asBoolean(false)) {
            throw unavailable();
        }
        Map<Long, ServiceInfo> services = new LinkedHashMap<>();
        for (JsonNode node : body.path("data").path("services")) {
            if (node.hasNonNull("id")) {
                Long statusId = node.hasNonNull("status_id")
                        ? Long.valueOf(node.get("status_id").asLong())
                        : (node.path("status").hasNonNull("id") ? Long.valueOf(node.path("status").get("id").asLong()) : null);
                services.put(node.get("id").asLong(), new ServiceInfo(
                        node.hasNonNull("client_id") ? node.get("client_id").asLong() : null, statusId));
            }
        }
        return services;
    }

    /** Extra asociado a un servicio: nombre y valor del catálogo de ms-service. */
    public record ExtraInfo(Long extraId, String name, java.math.BigDecimal value) {
    }

    /** Extras vigentes del servicio, por {@code extra_id}. */
    public Map<Long, ExtraInfo> extras(Long serviceId) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/api/v1/service/{serviceId}/extras").queryParam("limit", 1000).build(serviceId))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw unavailable();
        }
        if (body == null || !body.path("success").asBoolean(false)) {
            throw unavailable();
        }
        Map<Long, ExtraInfo> extras = new LinkedHashMap<>();
        for (JsonNode node : body.path("data").path("extras")) {
            if (node.hasNonNull("extra_id") && !node.hasNonNull("delete_at")) {
                long extraId = node.get("extra_id").asLong();
                extras.put(extraId, new ExtraInfo(extraId, node.path("name").asText(null),
                        node.hasNonNull("value") ? node.get("value").decimalValue() : null));
            }
        }
        return extras;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "SERVICE_UNAVAILABLE", "No se pudo consultar el servicio");
    }
}
