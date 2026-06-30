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

    // sales_count는 동시성 안전을 위해 ProductMetricsRepository.incrementSalesCount(원자 UPSERT)로만 갱신한다.
    // 엔티티 read-modify-write 메서드는 lost update 위험이 있어 의도적으로 두지 않는다.

    public Long getProductId() { return productId; }
    public long getLikeCount() { return likeCount; }
    public long getSalesCount() { return salesCount; }
}
