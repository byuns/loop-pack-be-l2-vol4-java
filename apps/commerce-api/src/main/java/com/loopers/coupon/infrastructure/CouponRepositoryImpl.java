package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponModel;
import com.loopers.coupon.domain.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository couponJpaRepository;

    @Override
    public CouponModel save(CouponModel coupon) {
        return couponJpaRepository.save(coupon);
    }

    @Override
    public Optional<CouponModel> findActiveById(Long id) {
        return couponJpaRepository.findActiveById(id);
    }

    @Override
    public Optional<CouponModel> findById(Long id) {
        return couponJpaRepository.findById(id);
    }

    @Override
    public List<CouponModel> findAllActive(int page, int size) {
        return couponJpaRepository.findAllActive(PageRequest.of(page, size)).getContent();
    }

    @Override
    public List<CouponModel> findAllByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return couponJpaRepository.findAllByIds(ids);
    }
}
