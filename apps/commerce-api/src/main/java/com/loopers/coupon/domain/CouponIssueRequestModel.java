package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(name = "coupon_issue_requests",
    uniqueConstraints = @UniqueConstraint(columnNames = {"coupon_id", "user_id"}))
public class CouponIssueRequestModel extends BaseEntity {

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponIssueRequestStatus status;

    protected CouponIssueRequestModel() {}

    public CouponIssueRequestModel(Long couponId, Long userId) {
        if (couponId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 ID는 비어있을 수 없습니다.");
        }
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 비어있을 수 없습니다.");
        }
        this.couponId = couponId;
        this.userId = userId;
        this.status = CouponIssueRequestStatus.PENDING;
    }
}
