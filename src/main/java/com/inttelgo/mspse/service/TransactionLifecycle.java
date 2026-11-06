package com.inttelgo.mspse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.domain.DetalleTransaccion;
import com.inttelgo.mspse.domain.Transaccion;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.exception.NotFoundException;
import com.inttelgo.mspse.repository.DetalleTransaccionRepository;
import com.inttelgo.mspse.repository.TransaccionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cambios de estado de una transacción. Cada método bloquea la fila y revisa el estado actual, así
 * la consulta del landing y el job pueden correr a la vez sin aprobar dos veces.
 */
@Service
@Transactional
public class TransactionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransactionLifecycle.class);

    private final TransaccionRepository repository;
    private final DetalleTransaccionRepository detailRepository;
    private final EstadoTransaccionService statusService;
    private final GenerateUrlService urlService;
    private final AppProperties properties;
    private final ZoneId zone;

    public TransactionLifecycle(
            TransaccionRepository repository,
            DetalleTransaccionRepository detailRepository,
            EstadoTransaccionService statusService,
            GenerateUrlService urlService,
            AppProperties properties) {
        this.repository = repository;
        this.detailRepository = detailRepository;
        this.statusService = statusService;
        this.urlService = urlService;
        this.properties = properties;
        this.zone = ZoneId.of(properties.zone());
    }

    /** Guarda la transacción PENDIENTE antes de llamar a PSE para no perder el registro si algo falla después. */
    public Transaccion createPending(Transaccion transaction, List<DetalleTransaccion> details) {
        transaction.setStatus(statusService.require(TransactionStatus.PENDIENTE));
        Transaccion saved = repository.save(transaction);
        details.forEach(detail -> detail.setTransaction(saved));
        detailRepository.saveAll(details);
        return saved;
    }

    public void markStarted(Long id, String trazabilityCode, String transactionCycle) {
        Transaccion transaction = lock(id);
        transaction.setCodeTrazability(trazabilityCode);
        transaction.setTrasnsactionCycle(transactionCycle);
        transaction.setUpdateAt(Instant.now());
    }

    /** PSE no aceptó la transacción: queda FALLIDA y no se consulta más. */
    public void markRejectedOnStart(Long id) {
        Transaccion transaction = lock(id);
        Instant now = Instant.now();
        transaction.setStatus(statusService.require(TransactionStatus.FALLIDA));
        transaction.setResponseAt(now);
        transaction.setUpdateAt(now);
    }

    /**
     * Aplica la respuesta de {@code GetTransactionInformationNF}. Devuelve {@code true} solo a quien
     * pasó la transacción de PENDIENTE a APROBADA (ese llamador finaliza en PSE).
     */
    public boolean applyPseState(Long id, JsonNode info) {
        Transaccion transaction = lock(id);
        if (info == null || !TransactionStatus.PENDIENTE.is(transaction.getStatus())) {
            return false;
        }
        TransactionStatus next = TransactionStatus.fromPse(info.path("transactionState").asText(null));
        Instant now = Instant.now();
        transaction.setUpdateAt(now);
        if (next == null || next == TransactionStatus.PENDIENTE) {
            return false;
        }
        transaction.setStatus(statusService.require(next));
        transaction.setResponseAt(now);
        String cycle = text(info, "transactionCycle");
        if (cycle != null) {
            transaction.setTrasnsactionCycle(cycle);
        }
        String institution = text(info, "financialInstitutionCode");
        if (institution != null) {
            transaction.setFinancialInstitutionCode(institution);
        }
        if ((transaction.getBankName() == null || transaction.getBankName().isBlank()) && text(info, "financialInstitutionName") != null) {
            transaction.setBankName(text(info, "financialInstitutionName"));
        }
        Instant processed = parseDate(text(info, "bankProcessDate"));
        if (processed != null) {
            transaction.setBankProcessDate(processed);
        }
        if (next == TransactionStatus.APROBADA && transaction.getUrlGenerated() != null) {
            urlService.markPaid(transaction.getUrlGenerated(), properties.build().systemUserId());
        }
        log.info("Transacción {} (ticket {}) pasó a {}", id, transaction.getNumberTicket(), next);
        return next == TransactionStatus.APROBADA;
    }

    /** CANCELADA si sigue pendiente y superó el plazo. */
    public boolean cancelIfStale(Long id, Duration maxAge) {
        Transaccion transaction = lock(id);
        if (!TransactionStatus.PENDIENTE.is(transaction.getStatus())
                || transaction.getCreateAt().isAfter(Instant.now().minus(maxAge))) {
            return false;
        }
        transaction.setStatus(statusService.require(TransactionStatus.CANCELADA));
        transaction.setUpdateAt(Instant.now());
        log.info("Transacción {} (ticket {}) cancelada por superar {} horas pendiente",
                id, transaction.getNumberTicket(), maxAge.toHours());
        return true;
    }

    public Transaccion lock(Long id) {
        return repository.lockById(id).orElseThrow(() -> new NotFoundException("La transacción no existe"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** PSE manda la fecha del banco en ISO con o sin zona, o solo fecha. */
    Instant parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // sigue con los demás formatos
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // sigue con los demás formatos
        }
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException exception) {
            log.warn("Fecha de proceso del banco no reconocida: {}", value);
            return null;
        }
    }
}
