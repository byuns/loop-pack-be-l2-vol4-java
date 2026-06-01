package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.coupon.domain.CouponModel;
import com.loopers.coupon.domain.CouponStatus;

public record CouponIssueInfo(Long id, Long couponId, Long userId, CouponStatus status) {

    public static CouponIssueInfo from(CouponIssueModel issue, CouponModel coupon) {
        CouponStatus computedStatus;
        if (issue.getStatus() == CouponStatus.USED) {
            computedStatus = CouponStatus.USED;
        } else if (coupon.isExpired()) {
            computedStatus = CouponStatus.EXPIRED;
        } else {
            computedStatus = CouponStatus.AVAILABLE;
        }
        return new CouponIssueInfo(issue.getId(), issue.getCouponId(), issue.getUserId(), computedStatus);
    }

    public static CouponIssueInfo from(CouponIssueModel issue) {
        return new CouponIssueInfo(issue.getId(), issue.getCouponId(), issue.getUserId(), issue.getStatus());
    }
}
