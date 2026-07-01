package com.loopers.coupon.domain;

import java.util.Optional;

public interface CouponIssueRequestRepository {
    CouponIssueRequestModel save(CouponIssueRequestModel request);
    Optional<CouponIssueRequestModel> findById(Long id);
    Optional<CouponIssueRequestModel> findByCouponIdAndUserId(Long couponId, Long userId);
}
