package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueRequestModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRequestJpaRepository extends JpaRepository<CouponIssueRequestModel, Long> {
}
