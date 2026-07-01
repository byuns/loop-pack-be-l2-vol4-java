package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.coupon.domain.CouponIssueRepository;
import com.loopers.coupon.domain.CouponIssueRequestModel;
import com.loopers.coupon.domain.CouponIssueRequestRepository;
import com.loopers.coupon.domain.CouponIssueRequestStatus;
import com.loopers.coupon.domain.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueRequestRepository couponIssueRequestRepository;

    @Transactional
    public void handleCouponIssueRequest(Long requestId, Long couponId, Long userId) {
        CouponIssueRequestModel request = couponIssueRequestRepository.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("발급 요청을 찾을 수 없습니다. requestId=" + requestId));

        // 멱등성: 이미 처리된 요청이면 skip
        if (request.getStatus() != CouponIssueRequestStatus.PENDING) {
            log.info("[CouponIssueService] 이미 처리된 요청 skip requestId={} status={}", requestId, request.getStatus());
            return;
        }

        // 중복 발급 체크 (coupon_issues UNIQUE 제약 전 사전 차단)
        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            log.warn("[CouponIssueService] 중복 발급 요청 차단 couponId={} userId={}", couponId, userId);
            request.markAsFailed();
            couponIssueRequestRepository.save(request);
            return;
        }

        // 원자 감소: remaining_count > 0 인 경우에만 성공 (DB 레벨 수량 보장)
        int updated = couponRepository.decrementRemainingCount(couponId);
        if (updated == 0) {
            log.info("[CouponIssueService] 수량 소진 couponId={} userId={}", couponId, userId);
            request.markAsFailed();
            couponIssueRequestRepository.save(request);
            return;
        }

        couponIssueRepository.save(new CouponIssueModel(couponId, userId));
        request.markAsIssued();
        couponIssueRequestRepository.save(request);
        log.info("[CouponIssueService] 쿠폰 발급 완료 requestId={} couponId={} userId={}", requestId, couponId, userId);
    }
}
