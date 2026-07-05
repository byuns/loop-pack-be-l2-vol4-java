package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueRequestModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssueRequestJpaRepository extends JpaRepository<CouponIssueRequestModel, Long> {
    Optional<CouponIssueRequestModel> findByCouponIdAndUserId(Long couponId, Long userId);
}
