package com.inttelgo.mspse.service;

import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.domain.DetalleTransaccion;
import com.inttelgo.mspse.domain.Transaccion;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.repository.DetalleTransaccionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lleva a ms-build una transacción APROBADA:
 * <ul>
 *   <li>Las líneas con {@code service_id} forman una factura (una por transacción) y su id queda en {@code build_id}.
 *       Los extras van en su propia línea con {@code service_id} + {@code extra_id}.</li>
 *   <li>Cada línea con {@code collection_build_id} registra el pago de la siguiente cuota y su id queda en {@code payment_id}.</li>
 * </ul>
 * Es idempotente: lo que ya tiene id no se vuelve a crear, así el job puede reintentar lo que falló.
 * Cada paso que sale bien se guarda aunque falle el siguiente.
 */
@Service
public class TransactionSettlement {

    private static final Logger log = LoggerFactory.getLogger(TransactionSettlement.class);

    private final TransactionLifecycle lifecycle;
    private final DetalleTransaccionRepository detailRepository;
    private final BuildClient buildClient;
    private final BillingPeriod billingPeriod;
    private final AppProperties.Build config;

    public TransactionSettlement(
            TransactionLifecycle lifecycle,
            DetalleTransaccionRepository detailRepository,
            BuildClient buildClient,
            BillingPeriod billingPeriod,
            AppProperties properties) {
        this.lifecycle = lifecycle;
        this.detailRepository = detailRepository;
        this.buildClient = buildClient;
        this.billingPeriod = billingPeriod;
        this.config = properties.build();
    }

    /** Errores de lo que no se pudo crear; vacío si todo quedó en ms-build. */
    public record Result(@com.fasterxml.jackson.annotation.JsonProperty("build_id") Long buildId, List<String> errors) {
    }

    @Transactional
    public Result settle(Long transactionId) {
        Transaccion transaction = lifecycle.lock(transactionId);
        if (!TransactionStatus.APROBADA.is(transaction.getStatus())) {
            return new Result(transaction.getBuildId(), List.of("La transacción no está aprobada"));
        }
        List<DetalleTransaccion> details = detailRepository.findByTransaction_IdOrderByIdAsc(transactionId);
        List<String> errors = new ArrayList<>();

        List<DetalleTransaccion> serviceLines = details.stream().filter(line -> line.getServiceId() != null).toList();
        if (!serviceLines.isEmpty() && transaction.getBuildId() == null) {
            try {
                transaction.setBuildId(createBuild(transaction, serviceLines));
                log.info("Transacción {}: factura {} creada en ms-build", transactionId, transaction.getBuildId());
            } catch (RuntimeException exception) {
                log.error("Transacción {}: no se pudo crear la factura: {}", transactionId, exception.getMessage());
                errors.add("Factura: " + exception.getMessage());
            }
        }

        for (DetalleTransaccion line : details) {
            if (line.getCollectionBuildId() == null || line.getPaymentId() != null) {
                continue;
            }
            try {
                BuildClient.Collection collection = buildClient.collection(line.getCollectionBuildId());
                line.setPaymentId(buildClient.createPayment(collection.id(), collection.nextQuota(), line.getValue(),
                        description(transaction, line)));
                detailRepository.save(line);
                log.info("Transacción {}: pago {} creado en el cobro {}", transactionId, line.getPaymentId(), collection.id());
            } catch (RuntimeException exception) {
                log.error("Transacción {}: no se pudo registrar el pago del cobro {}: {}",
                        transactionId, line.getCollectionBuildId(), exception.getMessage());
                errors.add("Cobro " + line.getCollectionBuildId() + ": " + exception.getMessage());
            }
        }
        return new Result(transaction.getBuildId(), errors);
    }

    private Long createBuild(Transaccion transaction, List<DetalleTransaccion> lines) {
        BillingPeriod.Target target = billingPeriod.resolve(lines.stream().map(DetalleTransaccion::getServiceId).distinct().toList());
        List<BuildClient.BuildLine> buildLines = lines.stream()
                .map(line -> new BuildClient.BuildLine(
                        line.getServiceId(),
                        line.getExtraId(),
                        truncate(line.getDescription(), 200),
                        line.getInternetValue(),
                        line.getTelephonyValue(),
                        line.getTelevisionValue(),
                        // ms-build recibe el valor sin descuento y aplica el % él mismo: llega al mismo neto.
                        line.getValue().add(CatalogSupport.zero(line.getDiscount())),
                        null))
                .toList();
        BuildClient.BuildRequest request = new BuildClient.BuildRequest(
                target.buildAt(),
                config.methodId(),
                config.officeId(),
                config.statusId(),
                transaction.getDiscount() == null ? null : BigDecimal.valueOf(transaction.getDiscount()),
                "Pago PSE CUS " + Objects.toString(transaction.getCodeTrazability(), "-") + " ticket " + transaction.getNumberTicket(),
                config.electronic(),
                buildLines);
        return buildClient.createBuild(target.clientId(), request);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String description(Transaccion transaction, DetalleTransaccion line) {
        String base = line.getDescription() == null || line.getDescription().isBlank() ? "Pago PSE" : line.getDescription().trim();
        String text = base + " - CUS " + Objects.toString(transaction.getCodeTrazability(), "-");
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
