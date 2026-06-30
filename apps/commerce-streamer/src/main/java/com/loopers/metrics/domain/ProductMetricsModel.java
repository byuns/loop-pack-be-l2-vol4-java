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

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    // 마지막으로 반영한 조회 이벤트의 occurredAt(epoch millis). "최신만 반영" 가드용. nullable(최초엔 없음)
    @Column(name = "last_view_event_at")
    private Long lastViewEventAtMillis;

    protected ProductMetricsModel() {}

    public ProductMetricsModel(Long productId) {
        this.productId = productId;
        this.likeCount = 0;
        this.salesCount = 0;
        this.viewCount = 0;
    }

    public void incrementLikeCount() {
        this.likeCount++;
    }

    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    /**
     * 조회 이벤트를 반영한다. occurredAt 기준 "최신만 반영" —
     * 더 오래된(stale) 이벤트는 무시한다. 같은 timestamp의 서로 다른 이벤트는 event_handled가 중복을 막으므로 반영해도 안전.
     * catalog-events는 productId 키라 같은 상품이 단일 파티션에서 순서 보장 → occurredAt이 단조 증가하므로
     * 이 가드는 재발행 등 진짜 stale 이벤트만 떨군다(정상 조회는 누락되지 않음).
     */
    public void applyView(long occurredAtMillis) {
        if (lastViewEventAtMillis != null && occurredAtMillis < lastViewEventAtMillis) {
            return;
        }
        this.viewCount++;
        this.lastViewEventAtMillis = occurredAtMillis;
    }

    // sales_count는 동시성 안전을 위해 ProductMetricsRepository.incrementSalesCount(원자 UPSERT)로만 갱신한다.
    // 엔티티 read-modify-write 메서드는 lost update 위험이 있어 의도적으로 두지 않는다.

    public Long getProductId() { return productId; }
    public long getLikeCount() { return likeCount; }
    public long getSalesCount() { return salesCount; }
    public long getViewCount() { return viewCount; }
    public Long getLastViewEventAtMillis() { return lastViewEventAtMillis; }
}
