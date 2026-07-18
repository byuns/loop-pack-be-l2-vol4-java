package com.loopers.payment.application;

/**
 * ORDER_CONFIRMED payload의 items 한 건. self-contained payload라 추가 API 호출 없이 집계 가능.
 * price는 개당 단가 — 랭킹 점수(price × quantity) 계산에 사용한다.
 */
public record SalesItem(Long productId, long quantity, long price) {
}
