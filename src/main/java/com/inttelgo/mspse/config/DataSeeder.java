package com.inttelgo.mspse.config;

import com.inttelgo.mspse.domain.Auditable;
import com.inttelgo.mspse.domain.EstadoTransaccion;
import com.inttelgo.mspse.domain.EstadoUrl;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.domain.UrlStatus;
import com.inttelgo.mspse.repository.EstadoTransaccionRepository;
import com.inttelgo.mspse.repository.EstadoUrlRepository;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra los estados en el orden de {@link TransactionStatus} y {@link UrlStatus}: el código usa esos
 * ids. Cada catálogo se siembra solo si está vacío.
 */
@Component
@ConditionalOnProperty(name = "app.seed", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements ApplicationRunner {

    private static final long SEED_ACTOR = 1L;

    private final EstadoTransaccionRepository statusRepository;
    private final EstadoUrlRepository urlStatusRepository;

    public DataSeeder(EstadoTransaccionRepository statusRepository, EstadoUrlRepository urlStatusRepository) {
        this.statusRepository = statusRepository;
        this.urlStatusRepository = urlStatusRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        if (statusRepository.count() == 0) {
            for (TransactionStatus value : TransactionStatus.values()) {
                EstadoTransaccion status = new EstadoTransaccion();
                status.setStatus(value.name());
                statusRepository.save(stamp(status, now));
            }
        }
        if (urlStatusRepository.count() == 0) {
            for (UrlStatus value : UrlStatus.values()) {
                EstadoUrl status = new EstadoUrl();
                status.setStatus(value.name());
                urlStatusRepository.save(stamp(status, now));
            }
        }
    }

    private static <T extends Auditable> T stamp(T entity, Instant now) {
        entity.setCreateAt(now);
        entity.setUpdateAt(now);
        entity.setCreateBy(SEED_ACTOR);
        entity.setUpdateBy(SEED_ACTOR);
        return entity;
    }
}
