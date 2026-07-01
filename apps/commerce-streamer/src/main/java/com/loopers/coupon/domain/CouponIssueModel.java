package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(name = "coupon_issues",
    uniqueConstraints = @UniqueConstraint(columnNames = {"coupon_id", "user_id"}))
public class CouponIssueModel extends BaseEntity {

    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    private CouponStatus status;

    protected CouponIssueModel() {}

    public CouponIssueModel(Long couponId, Long userId) {
        this.couponId = couponId;
        this.userId = userId;
        this.status = CouponStatus.AVAILABLE;
    }
}
