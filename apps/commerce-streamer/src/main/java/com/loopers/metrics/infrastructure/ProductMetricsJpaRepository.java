package com.loopers.metrics.infrastructure;

import com.loopers.metrics.domain.ProductMetricsModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetricsModel, Long> {
    Optional<ProductMetricsModel> findByProductId(Long productId);
}
