package com.loopers.coupon.domain;

public interface CouponIssueRepository {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
    CouponIssueModel save(CouponIssueModel issue);
}
