package com.inttelgo.mspse.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    /**
     * Línea a pagar. Lleva {@code service_id} (entra a la factura) o {@code collection_build_id}
     * (paga la siguiente cuota del cobro), no las dos.
     */
    public record DetailLine(
            @Size(max = 255) String description,
            @Positive @JsonProperty("service_id") Long serviceId,
            /** Extra del servicio (ms-service). Va con {@code service_id}, en una línea aparte de la del plan. */
            @Positive @JsonProperty("extra_id") Long extraId,
            @Positive @JsonProperty("collection_build_id") Long collectionBuildId,
            @PositiveOrZero @JsonProperty("internet_value") BigDecimal internetValue,
            @PositiveOrZero @JsonProperty("telephony_value") BigDecimal telephonyValue,
            @PositiveOrZero @JsonProperty("television_value") BigDecimal televisionValue,
            /** Valor de la línea sin descuento. El % de la transacción se aplica a las líneas con servicio. */
            @NotNull @Positive BigDecimal value) {
    }

    /**
     * Inicio de pago desde el landing. Con {@code url_generated} (hash de la URL de pago) el valor sale
     * de la URL y no se mandan líneas; sin él, el valor es la suma de {@code details} con el descuento
     * aplicado. La descripción del pago la arma el servidor.
     */
    public record Create(
            @NotBlank @Size(max = 20) @JsonProperty("financial_institution_code") String financialInstitutionCode,
            @Size(max = 255) @JsonProperty("bank_name") String bankName,
            @NotNull @Min(0) @Max(1) @JsonProperty("user_type") Integer userType,
            @NotBlank @Size(max = 10) @JsonProperty("type_document") String typeDocument,
            @NotBlank @Size(max = 30) String identification,
            @NotBlank @Size(max = 255) @JsonProperty("full_name") String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 255) String address,
            /** Porcentaje de descuento (1 a 100) sobre las líneas con servicio, como en ms-build. */
            @Min(1) @Max(100) Integer discount,
            @Size(max = 300) @JsonProperty("url_generated") String urlGenerated,
            @JsonProperty("recaptcha_token") String recaptchaToken,
            List<@Valid DetailLine> details) {
    }

    /** Respuesta al iniciar: el landing redirige a {@code pse_url}. */
    public record Started(
            @JsonProperty("number_ticket") String numberTicket,
            @JsonProperty("ticket_hash") String ticketHash,
            @JsonProperty("code_trazability") String codeTrazability,
            @JsonProperty("pse_url") String pseUrl,
            @JsonProperty("transaction_cycle") String transactionCycle,
            @JsonProperty("return_code") String returnCode) {
    }

    public record Detail(
            Long id,
            String description,
            @JsonProperty("internet_value") BigDecimal internetValue,
            @JsonProperty("telephony_value") BigDecimal telephonyValue,
            @JsonProperty("television_value") BigDecimal televisionValue,
            BigDecimal value,
            BigDecimal discount,
            @JsonProperty("service_id") Long serviceId,
            @JsonProperty("extra_id") Long extraId,
            @JsonProperty("payment_id") Long paymentId,
            @JsonProperty("collection_build_id") Long collectionBuildId) {
    }

    public record Response(
            Long id,
            @JsonProperty("number_ticket") String numberTicket,
            @JsonProperty("code_trazability") String codeTrazability,
            @JsonProperty("transaction_value") BigDecimal transactionValue,
            Integer discount,
            @JsonProperty("iva_value") BigDecimal ivaValue,
            @JsonProperty("bank_code") String bankCode,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("type_document") String typeDocument,
            String identification,
            @JsonProperty("full_name") String fullName,
            String email,
            String phone,
            @JsonProperty("create_at") Instant createAt,
            @JsonProperty("create_by") Long createBy,
            @JsonProperty("response_at") Instant responseAt,
            @JsonProperty("update_at") Instant updateAt,
            @JsonProperty("first_reference") String firstReference,
            @JsonProperty("second_reference") String secondReference,
            @JsonProperty("thirth_reference") String thirthReference,
            String entitycode,
            @JsonProperty("financial_institutionCode") String financialInstitutionCode,
            @JsonProperty("service_code") String serviceCode,
            @JsonProperty("bank_process_date") Instant bankProcessDate,
            @JsonProperty("trasnsaction_cycle") String trasnsactionCycle,
            @JsonProperty("payment_description") String paymentDescription,
            String address,
            @JsonProperty("urlGenerated") Long urlGenerated,
            @JsonProperty("status_id") Long statusId,
            CatalogDtos.NamedItem status,
            @JsonProperty("build_id") Long buildId,
            @JsonProperty("collection_build_id") Long collectionBuildId,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }
}
