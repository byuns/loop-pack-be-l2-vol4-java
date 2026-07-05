package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponIssueRequestModel;
import com.loopers.coupon.domain.CouponIssueRequestStatus;

public record CouponIssueRequestInfo(Long requestId, Long couponId, Long userId, CouponIssueRequestStatus status) {

    public static CouponIssueRequestInfo from(CouponIssueRequestModel model) {
        return new CouponIssueRequestInfo(
            model.getId(),
            model.getCouponId(),
            model.getUserId(),
            model.getStatus()
        );
    }
}
