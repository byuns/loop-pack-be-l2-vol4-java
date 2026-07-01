package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueRequestModel;
import com.loopers.coupon.domain.CouponIssueRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponIssueRequestRepositoryImpl implements CouponIssueRequestRepository {

    private final CouponIssueRequestJpaRepository jpaRepository;

    @Override
    public CouponIssueRequestModel save(CouponIssueRequestModel request) {
        return jpaRepository.save(request);
    }

    @Override
    public Optional<CouponIssueRequestModel> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<CouponIssueRequestModel> findByCouponIdAndUserId(Long couponId, Long userId) {
        return jpaRepository.findByCouponIdAndUserId(couponId, userId);
    }
}
