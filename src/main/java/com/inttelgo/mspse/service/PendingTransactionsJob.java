package com.inttelgo.mspse.service;

import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.repository.TransaccionRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Antes {@code verificarTransaccionesPendientes.js}: cada pocos minutos consulta en PSE las pendientes,
 * cancela las que superan el plazo sin respuesta y reintenta en ms-build lo que quedó sin crear.
 */
@Component
public class PendingTransactionsJob {

    private static final Logger log = LoggerFactory.getLogger(PendingTransactionsJob.class);
    private static final Duration SETTLE_WINDOW = Duration.ofDays(30);

    private final TransaccionRepository repository;
    private final PseService pseService;
    private final TransactionLifecycle lifecycle;
    private final TransactionSettlement settlement;
    private final AppProperties.Pending config;

    public PendingTransactionsJob(
            TransaccionRepository repository,
            PseService pseService,
            TransactionLifecycle lifecycle,
            TransactionSettlement settlement,
            AppProperties properties) {
        this.repository = repository;
        this.pseService = pseService;
        this.lifecycle = lifecycle;
        this.settlement = settlement;
        this.config = properties.pending();
    }

    @Scheduled(cron = "${app.pending.cron:0 */3 * * * *}")
    public void scheduled() {
        if (config.enabled()) {
            run();
        }
    }

    public void run() {
        Duration maxAge = Duration.ofHours(Math.max(config.maxHours(), 1));
        for (Long id : repository.idsByStatus(TransactionStatus.PENDIENTE.id())) {
            boolean answered;
            try {
                answered = pseService.refresh(id);
            } catch (RuntimeException exception) {
                // Sin respuesta de PSE no se cancela: podría estar aprobada.
                log.error("Error verificando la transacción {}: {}", id, exception.getMessage());
                continue;
            }
            try {
                if (answered || repository.findById(id).map(t -> t.getCodeTrazability() == null).orElse(false)) {
                    lifecycle.cancelIfStale(id, maxAge);
                }
            } catch (RuntimeException exception) {
                log.error("Error cancelando la transacción {}: {}", id, exception.getMessage());
            }
        }
        for (Long id : repository.unsettled(TransactionStatus.APROBADA.id(), Instant.now().minus(SETTLE_WINDOW))) {
            try {
                settlement.settle(id);
            } catch (RuntimeException exception) {
                log.error("Error conciliando la transacción {} con ms-build: {}", id, exception.getMessage());
            }
        }
    }
}
