package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "coupons")
public class CouponModel extends BaseEntity {

    private String name;

    @Enumerated(EnumType.STRING)
    private CouponType type;

    private Long value;

    @Column(name = "min_order_amount")
    private Long minOrderAmount;

    @Column(name = "expired_at")
    private ZonedDateTime expiredAt;

    @Column(name = "max_count")
    private Integer maxCount;

    @Column(name = "remaining_count")
    private Integer remainingCount;

    protected CouponModel() {}

    public CouponModel(String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt) {
        this(name, type, value, minOrderAmount, expiredAt, null);
    }

    public CouponModel(String name, CouponType type, Long value, Long minOrderAmount, ZonedDateTime expiredAt, Integer maxCount) {
        validate(name, type, value, expiredAt);
        this.name = name;
        this.type = type;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.expiredAt = expiredAt;
        this.maxCount = maxCount;
        this.remainingCount = maxCount;
    }

    public boolean isExpired() {
        return ZonedDateTime.now().isAfter(expiredAt);
    }

    public long calculateDiscount(long orderAmount) {
        if (minOrderAmount != null && orderAmount < minOrderAmount) {
            throw new CoreException(ErrorType.BAD_REQUEST, "최소 주문 금액 조건을 충족하지 않습니다.");
        }
        return switch (type) {
            case FIXED -> Math.min(value, orderAmount);
            case RATE -> orderAmount * value / 100;
        };
    }

    public void update(String name, Long value, Long minOrderAmount, ZonedDateTime expiredAt) {
        validate(name, this.type, value, expiredAt);
        this.name = name;
        this.value = value;
        this.minOrderAmount = minOrderAmount;
        this.expiredAt = expiredAt;
    }

    private void validate(String name, CouponType type, Long value, ZonedDateTime expiredAt) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 이름은 비어있을 수 없습니다.");
        }
        if (type == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 타입은 비어있을 수 없습니다.");
        }
        if (value == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 할인 값은 비어있을 수 없습니다.");
        }
        if (type == CouponType.FIXED && value < 1) {
            throw new CoreException(ErrorType.BAD_REQUEST, "정액 쿠폰 할인 금액은 1 이상이어야 합니다.");
        }
        if (type == CouponType.RATE && (value < 1 || value > 100)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "정률 쿠폰 할인율은 1~100 사이여야 합니다.");
        }
        if (expiredAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 만료 일시는 비어있을 수 없습니다.");
        }
    }
}
