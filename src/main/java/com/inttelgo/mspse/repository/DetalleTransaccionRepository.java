package com.inttelgo.mspse.repository;

import com.inttelgo.mspse.domain.DetalleTransaccion;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetalleTransaccionRepository extends JpaRepository<DetalleTransaccion, Long> {

    List<DetalleTransaccion> findByTransaction_IdOrderByIdAsc(Long transactionId);

    List<DetalleTransaccion> findByTransaction_IdInOrderByIdAsc(Collection<Long> transactionIds);
}
