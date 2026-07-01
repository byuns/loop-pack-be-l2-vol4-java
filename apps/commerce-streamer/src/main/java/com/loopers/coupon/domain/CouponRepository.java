package com.loopers.coupon.domain;

public interface CouponRepository {
    int decrementRemainingCount(Long couponId);
}
