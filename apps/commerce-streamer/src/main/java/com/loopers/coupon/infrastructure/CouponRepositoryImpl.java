package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public int decrementRemainingCount(Long couponId) {
        return jdbcTemplate.update(
            "UPDATE coupons SET remaining_count = remaining_count - 1, updated_at = NOW() WHERE id = ? AND remaining_count > 0",
            couponId
        );
    }
}
