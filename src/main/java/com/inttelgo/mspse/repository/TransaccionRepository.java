package com.inttelgo.mspse.repository;

import com.inttelgo.mspse.domain.Transaccion;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    Optional<Transaccion> findByNumberTicket(String numberTicket);

    boolean existsByNumberTicket(String numberTicket);

    boolean existsByStatus_Id(Long statusId);

    boolean existsByUrlGenerated_IdAndStatus_IdIn(Long urlId, List<Long> statusIds);

    /** Bloquea la fila para que la consulta del landing y el job no apliquen el mismo cambio dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaccion t where t.id = :id")
    Optional<Transaccion> lockById(@Param("id") Long id);

    @Query("select t.id from Transaccion t where t.status.id = :statusId order by t.id")
    List<Long> idsByStatus(@Param("statusId") Long statusId);

    /** Aprobadas desde {@code since} a las que les falta la factura o algún pago en ms-build. */
    @Query("""
            select distinct t.id from Transaccion t, DetalleTransaccion d
            where d.transaction = t and t.status.id = :statusId and t.createAt >= :since
              and ((d.serviceId is not null and t.buildId is null)
                or (d.collectionBuildId is not null and d.paymentId is null))
            order by t.id
            """)
    List<Long> unsettled(@Param("statusId") Long statusId, @Param("since") Instant since);
}
