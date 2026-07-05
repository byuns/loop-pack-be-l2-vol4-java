package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponInfo;
import com.loopers.coupon.application.CouponIssueInfo;
import com.loopers.coupon.domain.CouponStatus;
import com.loopers.coupon.domain.CouponType;

import java.time.ZonedDateTime;

public class CouponAdminV1Dto {

    public record CreateRequest(String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt, Integer maxCount) {}

    public record UpdateRequest(String name, Long value, Long minOrderAmount, ZonedDateTime expiredAt) {}

    public record CouponResponse(Long id, String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt, Integer maxCount, Integer remainingCount) {
        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(
                info.id(), info.name(), info.type(), info.value(), info.minOrderAmount(), info.expiredAt(),
                info.maxCount(), info.remainingCount()
            );
        }
    }

    public record CouponIssueResponse(Long id, Long couponId, Long userId, CouponStatus status) {
        public static CouponIssueResponse from(CouponIssueInfo info) {
            return new CouponIssueResponse(info.id(), info.couponId(), info.userId(), info.status());
        }
    }
}
