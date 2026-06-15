package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponIssueInfo;
import com.loopers.coupon.domain.CouponStatus;

public class CouponV1Dto {

    public record CouponIssueResponse(Long id, Long couponId, Long userId, CouponStatus status) {
        public static CouponIssueResponse from(CouponIssueInfo info) {
            return new CouponIssueResponse(info.id(), info.couponId(), info.userId(), info.status());
        }
    }
}
