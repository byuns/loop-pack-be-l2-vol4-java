package com.loopers.coupon.domain;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    CouponModel save(CouponModel coupon);
    Optional<CouponModel> findActiveById(Long id);
    Optional<CouponModel> findById(Long id);
    List<CouponModel> findAllActive(int page, int size);
    List<CouponModel> findAllByIds(List<Long> ids);
    int decrementRemainingCount(Long couponId);
}
