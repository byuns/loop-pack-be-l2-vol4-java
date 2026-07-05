package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponIssueInfo;
import com.loopers.coupon.application.CouponIssueRequestInfo;
import com.loopers.coupon.domain.CouponIssueRequestStatus;
import com.loopers.coupon.domain.CouponStatus;

public class CouponV1Dto {

    public record CouponIssueResponse(Long id, Long couponId, Long userId, CouponStatus status) {
        public static CouponIssueResponse from(CouponIssueInfo info) {
            return new CouponIssueResponse(info.id(), info.couponId(), info.userId(), info.status());
        }
    }

    public record CouponIssueRequestResponse(Long requestId, Long couponId, Long userId, CouponIssueRequestStatus status) {
        public static CouponIssueRequestResponse from(CouponIssueRequestInfo info) {
            return new CouponIssueRequestResponse(info.requestId(), info.couponId(), info.userId(), info.status());
        }
    }
}
