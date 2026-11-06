package com.inttelgo.mspse.repository;

import com.inttelgo.mspse.domain.EstadoTransaccion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoTransaccionRepository extends JpaRepository<EstadoTransaccion, Long> {

    List<EstadoTransaccion> findByDeleteAtIsNullOrderByIdAsc();

    boolean existsByStatusIgnoreCaseAndDeleteAtIsNull(String status);

    boolean existsByStatusIgnoreCaseAndDeleteAtIsNullAndIdNot(String status, Long id);
}
