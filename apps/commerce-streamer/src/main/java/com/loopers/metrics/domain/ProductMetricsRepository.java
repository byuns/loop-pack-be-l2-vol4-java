package com.loopers.metrics.domain;

import java.util.Optional;

public interface ProductMetricsRepository {
    Optional<ProductMetricsModel> findByProductId(Long productId);
    ProductMetricsModel save(ProductMetricsModel model);
}
