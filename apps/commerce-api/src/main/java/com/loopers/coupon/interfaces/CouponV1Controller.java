package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponFacade;
import com.loopers.coupon.application.CouponIssueInfo;
import com.loopers.coupon.application.CouponIssueRequestInfo;
import com.loopers.support.auth.CurrentUser;
import com.loopers.support.auth.LoginUser;
import com.loopers.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class CouponV1Controller {

    private final CouponFacade couponFacade;

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/coupons/{couponId}/issue")
    public ApiResponse<CouponV1Dto.CouponIssueRequestResponse> issueCoupon(
        @CurrentUser LoginUser loginUser,
        @PathVariable Long couponId
    ) {
        CouponIssueRequestInfo info = couponFacade.requestCouponIssue(couponId, loginUser.id());
        return ApiResponse.success(CouponV1Dto.CouponIssueRequestResponse.from(info));
    }

    @GetMapping("/api/v1/coupons/issue-requests/{requestId}")
    public ApiResponse<CouponV1Dto.CouponIssueRequestResponse> getIssueRequestStatus(
        @CurrentUser LoginUser loginUser,
        @PathVariable Long requestId
    ) {
        CouponIssueRequestInfo info = couponFacade.getCouponIssueRequestStatus(requestId, loginUser.id());
        return ApiResponse.success(CouponV1Dto.CouponIssueRequestResponse.from(info));
    }

    @GetMapping("/api/v1/users/me/coupons")
    public ApiResponse<List<CouponV1Dto.CouponIssueResponse>> getMyCoupons(
        @CurrentUser LoginUser loginUser
    ) {
        List<CouponIssueInfo> infos = couponFacade.getMyCoupons(loginUser.id());
        List<CouponV1Dto.CouponIssueResponse> responses = infos.stream()
            .map(CouponV1Dto.CouponIssueResponse::from)
            .toList();
        return ApiResponse.success(responses);
    }
}
