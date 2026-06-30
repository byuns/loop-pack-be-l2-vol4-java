package com.loopers.metrics.domain;

import java.util.Optional;

public interface ProductMetricsRepository {
    Optional<ProductMetricsModel> findByProductId(Long productId);
    ProductMetricsModel save(ProductMetricsModel model);

    /**
     * sales_count를 DB 레벨에서 원자적으로 증가시킨다 (없으면 새 row 생성).
     * order-events는 orderId로 파티셔닝되어 같은 productId가 여러 파티션·인스턴스에서 동시에 갱신될 수 있으므로,
     * read-modify-write 대신 원자 UPSERT로 lost update를 방지한다.
     */
    void incrementSalesCount(Long productId, long delta);
}
