package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponFacade;
import com.loopers.coupon.application.CouponInfo;
import com.loopers.coupon.application.CouponIssueInfo;
import com.loopers.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupons")
public class CouponAdminV1Controller {

    private final CouponFacade couponFacade;

    @PostMapping
    public ApiResponse<CouponAdminV1Dto.CouponResponse> createCoupon(
        @RequestBody CouponAdminV1Dto.CreateRequest request
    ) {
        CouponInfo info = couponFacade.createCoupon(
            request.name(), request.type(), request.value(), request.minOrderAmount(), request.expiredAt(), request.maxCount()
        );
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(info));
    }

    @GetMapping
    public ApiResponse<List<CouponAdminV1Dto.CouponResponse>> getCoupons(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        List<CouponInfo> infos = couponFacade.getCoupons(page, size);
        List<CouponAdminV1Dto.CouponResponse> responses = infos.stream()
            .map(CouponAdminV1Dto.CouponResponse::from)
            .toList();
        return ApiResponse.success(responses);
    }

    @GetMapping("/{couponId}")
    public ApiResponse<CouponAdminV1Dto.CouponResponse> getCoupon(@PathVariable Long couponId) {
        CouponInfo info = couponFacade.getCoupon(couponId);
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(info));
    }

    @PutMapping("/{couponId}")
    public ApiResponse<CouponAdminV1Dto.CouponResponse> updateCoupon(
        @PathVariable Long couponId,
        @RequestBody CouponAdminV1Dto.UpdateRequest request
    ) {
        CouponInfo info = couponFacade.updateCoupon(
            couponId, request.name(), request.value(), request.minOrderAmount(), request.expiredAt()
        );
        return ApiResponse.success(CouponAdminV1Dto.CouponResponse.from(info));
    }

    @DeleteMapping("/{couponId}")
    public ApiResponse<Void> deleteCoupon(@PathVariable Long couponId) {
        couponFacade.deleteCoupon(couponId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{couponId}/issues")
    public ApiResponse<List<CouponAdminV1Dto.CouponIssueResponse>> getCouponIssues(
        @PathVariable Long couponId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        List<CouponIssueInfo> infos = couponFacade.getCouponIssues(couponId, page, size);
        List<CouponAdminV1Dto.CouponIssueResponse> responses = infos.stream()
            .map(CouponAdminV1Dto.CouponIssueResponse::from)
            .toList();
        return ApiResponse.success(responses);
    }
}
