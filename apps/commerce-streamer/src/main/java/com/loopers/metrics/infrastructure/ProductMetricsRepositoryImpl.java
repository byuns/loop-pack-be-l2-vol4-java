package com.loopers.metrics.infrastructure;

import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {

    private final ProductMetricsJpaRepository jpa;

    @Override
    public Optional<ProductMetricsModel> findByProductId(Long productId) {
        return jpa.findByProductId(productId);
    }

    @Override
    public ProductMetricsModel save(ProductMetricsModel model) {
        return jpa.save(model);
    }
}
