package com.inttelgo.mspse.repository;

import com.inttelgo.mspse.domain.GenerateUrl;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerateUrlRepository extends JpaRepository<GenerateUrl, Long> {

    boolean existsByStatus_Id(Long statusId);
}
