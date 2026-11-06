package com.inttelgo.mspse.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record StatusRequest(@NotBlank @Size(max = 50) String status) {
    }

    public record StatusItem(
            Long id,
            String status,
            @JsonProperty("create_at") Instant createAt,
            @JsonProperty("create_by") Long createBy,
            @JsonProperty("update_at") Instant updateAt,
            @JsonProperty("update_by") Long updateBy) {
    }

    public record SelectItem(long value, String label) {
    }

    /** Relación embebida en respuestas (status, etc.). */
    public record NamedItem(Long id, String status) {
    }

    public record SelectList<T>(List<T> items) {
    }

    public record Page<T>(List<T> items, long total, int skip, int limit, boolean hasMore) {
    }
}
