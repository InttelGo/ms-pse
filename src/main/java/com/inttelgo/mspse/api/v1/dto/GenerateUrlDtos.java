package com.inttelgo.mspse.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class GenerateUrlDtos {

    private GenerateUrlDtos() {
    }

    public record Create(
            @NotBlank @Size(max = 255) String concept,
            @NotNull @Positive BigDecimal value,
            @NotNull @JsonProperty("expiry_at") LocalDate expiryAt) {
    }

    /** Campos nulos no se tocan. {@code status_id} acepta 1 = ACTIVA (reactivar) o 3 = DESHABILITADA (deshabilitar). */
    public record Update(
            @Size(max = 255) String concept,
            @Positive BigDecimal value,
            @JsonProperty("expiry_at") LocalDate expiryAt,
            @Positive @JsonProperty("status_id") Long statusId) {
    }

    public record Response(
            Long id,
            String url,
            String concept,
            BigDecimal value,
            @JsonProperty("expiry_at") LocalDate expiryAt,
            @JsonProperty("status_id") Long statusId,
            CatalogDtos.NamedItem status,
            boolean expired,
            @JsonProperty("create_at") Instant createAt,
            @JsonProperty("create_by") Long createBy,
            @JsonProperty("update_at") Instant updateAt,
            @JsonProperty("update_by") Long updateBy) {
    }

    /** Lo que ve el landing de una URL de pago. {@code payment} es el hash que se manda al iniciar el pago. */
    public record Public(
            String payment,
            String concept,
            BigDecimal value,
            @JsonProperty("expiry_at") LocalDate expiryAt) {
    }
}
