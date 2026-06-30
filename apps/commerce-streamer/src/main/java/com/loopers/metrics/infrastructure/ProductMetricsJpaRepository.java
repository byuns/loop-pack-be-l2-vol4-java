package com.loopers.metrics.infrastructure;

import com.loopers.metrics.domain.ProductMetricsModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductMetricsJpaRepository extends JpaRepository<ProductMetricsModel, Long> {
    Optional<ProductMetricsModel> findByProductId(Long productId);

    // product_id unique 키 충돌 시 sales_count를 원자적으로 누적 (lost update 방지)
    @Modifying
    @Query(value = "INSERT INTO product_metrics (product_id, like_count, sales_count, created_at, updated_at) "
        + "VALUES (:productId, 0, :delta, NOW(), NOW()) "
        + "ON DUPLICATE KEY UPDATE sales_count = sales_count + :delta, updated_at = NOW()", nativeQuery = true)
    void upsertSalesCount(@Param("productId") Long productId, @Param("delta") long delta);
}
