package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.coupon.domain.CouponIssueRepository;
import com.loopers.coupon.domain.CouponIssueRequestModel;
import com.loopers.coupon.domain.CouponIssueRequestRepository;
import com.loopers.coupon.domain.CouponModel;
import com.loopers.coupon.domain.CouponRepository;
import com.loopers.coupon.domain.CouponService;
import com.loopers.coupon.domain.CouponType;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.outbox.OutboxEvent;
import com.loopers.support.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class CouponFacade {

    private final CouponService couponService;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueRequestRepository couponIssueRequestRepository;
    private final OutboxRepository outboxRepository;

    @Transactional
    public CouponInfo createCoupon(String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt) {
        return createCoupon(name, type, value, minOrderAmount, expiredAt, null);
    }

    @Transactional
    public CouponInfo createCoupon(String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt, Integer maxCount) {
        CouponModel coupon = new CouponModel(name, type, value, minOrderAmount, expiredAt, maxCount);
        return CouponInfo.from(couponRepository.save(coupon));
    }

    @Transactional(readOnly = true)
    public List<CouponInfo> getCoupons(int page, int size) {
        return couponRepository.findAllActive(page, size).stream()
            .map(CouponInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public CouponInfo getCoupon(Long couponId) {
        CouponModel coupon = couponService.getOrThrow(couponRepository.findActiveById(couponId));
        return CouponInfo.from(coupon);
    }

    @Transactional
    public CouponInfo updateCoupon(Long couponId, String name, Long value, Long minOrderAmount, ZonedDateTime expiredAt) {
        CouponModel coupon = couponService.getOrThrow(couponRepository.findActiveById(couponId));
        coupon.update(name, value, minOrderAmount, expiredAt);
        return CouponInfo.from(couponRepository.save(coupon));
    }

    @Transactional
    public void deleteCoupon(Long couponId) {
        CouponModel coupon = couponService.getOrThrow(couponRepository.findActiveById(couponId));
        coupon.delete();
        couponRepository.save(coupon);
    }

    @Transactional(readOnly = true)
    public List<CouponIssueInfo> getCouponIssues(Long couponId, int page, int size) {
        couponService.getOrThrow(couponRepository.findActiveById(couponId));
        return couponIssueRepository.findAllByCouponId(couponId, page, size).stream()
            .map(CouponIssueInfo::from)
            .toList();
    }

    @Transactional
    public CouponIssueInfo issueCoupon(Long couponId, Long userId) {
        CouponModel coupon = couponService.getOrThrow(couponRepository.findActiveById(couponId));
        if (coupon.isExpired()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "만료된 쿠폰입니다.");
        }
        couponIssueRepository.findByUserIdAndCouponId(userId, couponId).ifPresent(issue -> {
            throw new CoreException(ErrorType.CONFLICT, "이미 발급받은 쿠폰입니다.");
        });
        CouponIssueModel issue = new CouponIssueModel(couponId, userId);
        return CouponIssueInfo.from(couponIssueRepository.save(issue));
    }

    @Transactional
    public CouponIssueRequestInfo requestCouponIssue(Long couponId, Long userId) {
        CouponModel coupon = couponService.getOrThrow(couponRepository.findActiveById(couponId));
        if (coupon.isExpired()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "만료된 쿠폰입니다.");
        }
        if (coupon.getMaxCount() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "선착순 발급을 지원하지 않는 쿠폰입니다.");
        }
        couponIssueRequestRepository.findByCouponIdAndUserId(couponId, userId).ifPresent(req -> {
            throw new CoreException(ErrorType.CONFLICT, "이미 발급 요청이 존재합니다.");
        });

        CouponIssueRequestModel request = new CouponIssueRequestModel(couponId, userId);
        CouponIssueRequestModel saved = couponIssueRequestRepository.save(request);

        outboxRepository.save(OutboxEvent.of(
            "coupon-issue-requests",
            "COUPON_ISSUE_REQUESTED",
            String.valueOf(couponId),
            "{\"requestId\":" + saved.getId()
                + ",\"couponId\":" + couponId
                + ",\"userId\":" + userId
                + ",\"eventType\":\"COUPON_ISSUE_REQUESTED\"}"
        ));

        return CouponIssueRequestInfo.from(saved);
    }

    @Transactional(readOnly = true)
    public CouponIssueRequestInfo getCouponIssueRequestStatus(Long requestId, Long userId) {
        CouponIssueRequestModel request = couponIssueRequestRepository.findById(requestId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "발급 요청을 찾을 수 없습니다."));
        if (!request.getUserId().equals(userId)) {
            throw new CoreException(ErrorType.FORBIDDEN, "본인의 발급 요청만 조회할 수 있습니다.");
        }
        return CouponIssueRequestInfo.from(request);
    }

    @Transactional(readOnly = true)
    public List<CouponIssueInfo> getMyCoupons(Long userId) {
        List<CouponIssueModel> issues = couponIssueRepository.findAllByUserId(userId);
        if (issues.isEmpty()) return List.of();

        List<Long> couponIds = issues.stream().map(CouponIssueModel::getCouponId).toList();
        Map<Long, CouponModel> couponMap = couponRepository.findAllByIds(couponIds).stream()
            .collect(Collectors.toMap(CouponModel::getId, Function.identity()));

        return issues.stream()
            .map(issue -> CouponIssueInfo.from(issue, couponMap.get(issue.getCouponId())))
            .toList();
    }
}
