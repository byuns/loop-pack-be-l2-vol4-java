package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.coupon.domain.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponIssueRepositoryImpl implements CouponIssueRepository {

    private final CouponIssueJpaRepository jpaRepository;

    @Override
    public boolean existsByCouponIdAndUserId(Long couponId, Long userId) {
        return jpaRepository.existsByCouponIdAndUserId(couponId, userId);
    }

    @Override
    public CouponIssueModel save(CouponIssueModel issue) {
        return jpaRepository.save(issue);
    }
}
