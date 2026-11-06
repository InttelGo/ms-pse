package com.inttelgo.mspse.repository;

import com.inttelgo.mspse.domain.EstadoUrl;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoUrlRepository extends JpaRepository<EstadoUrl, Long> {

    List<EstadoUrl> findByDeleteAtIsNullOrderByIdAsc();

    boolean existsByStatusIgnoreCaseAndDeleteAtIsNull(String status);

    boolean existsByStatusIgnoreCaseAndDeleteAtIsNullAndIdNot(String status, Long id);
}
