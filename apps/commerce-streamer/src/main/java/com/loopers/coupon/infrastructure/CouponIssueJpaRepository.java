package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueJpaRepository extends JpaRepository<CouponIssueModel, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
}
