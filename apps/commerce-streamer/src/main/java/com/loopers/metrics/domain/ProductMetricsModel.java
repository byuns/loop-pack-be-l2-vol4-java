package com.loopers.metrics.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_metrics")
public class ProductMetricsModel extends BaseEntity {

    @Column(name = "product_id", unique = true, nullable = false)
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "sales_count", nullable = false)
    private long salesCount;

    protected ProductMetricsModel() {}

    public ProductMetricsModel(Long productId) {
        this.productId = productId;
        this.likeCount = 0;
        this.salesCount = 0;
    }

    public void incrementLikeCount() {
        this.likeCount++;
    }

    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    public void addSalesCount(long delta) {
        this.salesCount += delta;
    }

    public Long getProductId() { return productId; }
    public long getLikeCount() { return likeCount; }
    public long getSalesCount() { return salesCount; }
}
