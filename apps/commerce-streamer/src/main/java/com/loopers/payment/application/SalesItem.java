package com.loopers.payment.application;

/**
 * ORDER_CONFIRMED payload의 items 한 건. self-contained payload라 추가 API 호출 없이 집계 가능.
 */
public record SalesItem(Long productId, long quantity) {
}
