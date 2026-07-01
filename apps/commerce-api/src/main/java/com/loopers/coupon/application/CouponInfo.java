package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponModel;
import com.loopers.coupon.domain.CouponType;

import java.time.ZonedDateTime;

public record CouponInfo(Long id, String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt, Integer maxCount, Integer remainingCount) {

    public static CouponInfo from(CouponModel model) {
        return new CouponInfo(
            model.getId(),
            model.getName(),
            model.getType(),
            model.getValue(),
            model.getMinOrderAmount(),
            model.getExpiredAt(),
            model.getMaxCount(),
            model.getRemainingCount()
        );
    }
}
