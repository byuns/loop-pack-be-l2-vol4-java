package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.coupon.domain.CouponStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CouponIssueJpaRepository extends JpaRepository<CouponIssueModel, Long> {

    Optional<CouponIssueModel> findByUserIdAndCouponId(Long userId, Long couponId);

    @Transactional
    @Modifying
    @Query("UPDATE CouponIssueModel c SET c.status = :newStatus WHERE c.id = :id AND c.status = :curStatus")
    int updateStatusIfAvailable(@Param("id") Long id,
                                @Param("newStatus") CouponStatus newStatus,
                                @Param("curStatus") CouponStatus curStatus);

    List<CouponIssueModel> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Page<CouponIssueModel> findAllByCouponId(Long couponId, Pageable pageable);
}
