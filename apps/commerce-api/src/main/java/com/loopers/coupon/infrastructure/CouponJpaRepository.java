package com.loopers.coupon.infrastructure;

import com.loopers.coupon.domain.CouponModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CouponJpaRepository extends JpaRepository<CouponModel, Long> {

    @Query("SELECT c FROM CouponModel c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<CouponModel> findActiveById(@Param("id") Long id);

    @Query("SELECT c FROM CouponModel c WHERE c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    Page<CouponModel> findAllActive(Pageable pageable);

    @Query("SELECT c FROM CouponModel c WHERE c.id IN :ids")
    List<CouponModel> findAllByIds(@Param("ids") List<Long> ids);
}
