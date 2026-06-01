# 04. ERD — 전체 테이블 구조 및 관계 정리

---

```mermaid
erDiagram
    users {
        BIGINT id PK
        VARCHAR login_id UK
        VARCHAR password
        VARCHAR email UK
        VARCHAR gender
        VARCHAR role
        BIGINT brand_id
        DATETIME created_at
        DATETIME updated_at
        DATETIME deleted_at
    }

    brands {
        BIGINT id PK
        VARCHAR name UK
        VARCHAR description
        DATETIME created_at
        DATETIME updated_at
        DATETIME deleted_at
    }

    products {
        BIGINT id PK
        BIGINT brand_id
        VARCHAR name
        BIGINT price
        VARCHAR description
        BIGINT like_count
        DATETIME created_at
        DATETIME updated_at
        DATETIME deleted_at
    }

    stocks {
        BIGINT id PK
        BIGINT product_id FK
        INT total_stock
        INT reserved_stock
        DATETIME created_at
        DATETIME updated_at
    }

    likes {
        BIGINT id PK
        BIGINT user_id
        BIGINT product_id
        DATETIME created_at
        DATETIME updated_at
    }

    coupons {
        BIGINT id PK
        VARCHAR name
        VARCHAR type
        BIGINT value
        BIGINT min_order_amount
        DATETIME expired_at
        DATETIME created_at
        DATETIME updated_at
        DATETIME deleted_at
    }

    coupon_issues {
        BIGINT id PK
        BIGINT coupon_id
        BIGINT user_id
        VARCHAR status
        DATETIME created_at
        DATETIME updated_at
    }

    orders {
        BIGINT id PK
        BIGINT user_id
        BIGINT coupon_issue_id
        BIGINT original_amount
        BIGINT discount_amount
        BIGINT final_amount
        VARCHAR status
        DATETIME created_at
        DATETIME updated_at
    }

    order_items {
        BIGINT id PK
        BIGINT order_id
        BIGINT product_id
        VARCHAR product_name
        BIGINT price
        INT quantity
        DATETIME created_at
        DATETIME updated_at
    }

    users }o--o| brands : "brand_id (nullable)"
    users ||--o{ likes : ""
    users ||--o{ orders : ""
    users ||--o{ coupon_issues : ""
    brands |o--o{ products : "brand_id (nullable)"
    products ||--|| stocks : ""
    products ||--o{ likes : ""
    orders ||--|{ order_items : ""
    coupons ||--o{ coupon_issues : ""
```

---

## 설계 결정 사항

### users — role + brand_id

어드민 역할을 `role` 컬럼으로 구분한다 (`CUSTOMER` / `BRAND_ADMIN` / `SUPER_ADMIN`). `brand_id`는 `BRAND_ADMIN`일 때만 값이 존재하며 자신이 담당하는 브랜드를 가리킨다. DB 레벨 FK를 걸지 않는 기존 정책을 따르며, 존재 여부는 애플리케이션에서 검증한다.

### BaseEntity / SoftDeletableEntity 분리
`BaseEntity`는 `id`, `created_at`, `updated_at`만 담당한다. 소프트 딜리트가 필요한 엔티티만 `SoftDeletableEntity`(extends BaseEntity)를 상속해 `deleted_at`, `delete()`, `restore()`를 갖는다.

| 상속 | 테이블 |
|------|--------|
| SoftDeletableEntity | users, brands, products, coupons |
| BaseEntity | stocks, likes, orders, order_items, coupon_issues |

`orders`와 `order_items`는 삭제 시나리오가 없고, 추후 주문 취소가 생기더라도 `deleted_at`이 아닌 `status` 필드로 상태를 표현하는 게 적합하다. `coupon_issues`는 발급 취소 시나리오가 없으므로 소프트 딜리트를 두지 않는다.

### likes — deleted_at 없음
하드 딜리트로 결정했으므로 `deleted_at` 컬럼을 두지 않는다. `BaseEntity`의 `id`, `created_at`, `updated_at`만 상속한다.

### FK 미사용
모든 테이블 간 관계는 DB 레벨 FK 없이 애플리케이션 레벨에서 검증한다. 시퀀스 다이어그램에 명시된 대로 각 유스케이스에서 참조 대상의 존재 여부를 사전 확인한다. ERD의 관계선은 논리적 참조를 나타낼 뿐 물리적 제약이 아니다.

### order_items — product_id 논리 참조
`product_id`는 스냅샷 참조용으로만 보관한다. 주문 이후 상품이 삭제되어도 주문 기록이 유지되어야 하므로 FK 제약을 걸지 않는다.

### orders — status + 금액 스냅샷
주문 상태는 `PENDING_PAYMENT` → `CONFIRMED` 두 단계로 관리한다. 주문 생성 시 `PENDING_PAYMENT`로 시작하며, 결제 완료(`pay/confirm`) 시 `CONFIRMED`로 전환된다.
`original_amount`(쿠폰 적용 전 합계), `discount_amount`(할인 금액), `final_amount`(최종 결제 금액)를 주문 생성 시점에 스냅샷으로 저장한다. 쿠폰 미적용 시 `discount_amount = 0`, `final_amount = original_amount`다.
`coupon_issue_id`는 사용된 발급 쿠폰 ID로 참조용으로만 보관하며 DB 레벨 FK는 두지 않는다.

### coupons — SoftDeletable
쿠폰 템플릿을 소프트 딜리트로 처리해 이미 발급된 쿠폰의 참조 정합성을 유지한다. 삭제 후에도 발급된 쿠폰은 만료 전까지 사용 가능하다. `type`은 `FIXED`(정액) / `RATE`(정률)이며 등록 후 변경 불가다. `value`는 FIXED: 1 이상, RATE: 1~100 범위로 제한하며 애플리케이션 레이어에서 검증한다. `min_order_amount`는 NULL 허용.

### coupon_issues — AVAILABLE/USED
발급 상태는 `AVAILABLE`(초기값)과 `USED`(사용 후)만 DB에 저장한다. `EXPIRED` 상태는 `coupons.expired_at`과 현재 시각을 비교해 애플리케이션 레이어에서 계산하며 별도 컬럼을 두지 않는다.

### stocks — 재고 선점 모델
`total_stock`은 전체 재고, `reserved_stock`은 결제 진입 후 선점된 수량이다. 실제 주문 가능 수량은 `total_stock - reserved_stock`으로 계산한다. 결제 완료 시 두 값을 동시에 차감해 확정 처리한다.

### products — like_count
초기값 0으로 생성되며 좋아요 등록·취소 시 `like_count = like_count ± 1` 형태의 DB 원자 UPDATE로 갱신된다.

---

## 제약 조건

| 테이블 | 종류 | 대상 컬럼 | 비고 |
|--------|------|-----------|------|
| users | UK | login_id | — |
| users | UK | email | — |
| users | CHECK | role, brand_id | role = 'BRAND_ADMIN'일 때만 brand_id 존재 |
| brands | UK | name | — |
| likes | UK | (user_id, product_id) | — |
| coupon_issues | UK | (coupon_id, user_id) | 동일 쿠폰 중복 발급 방지 |

---

## 인덱스 전략

| 테이블 | 인덱스명 | 컬럼 | 목적 |
|--------|----------|------|------|
| users | idx_users_brand_id | brand_id | BRAND_ADMIN의 브랜드 귀속 조회 |
| products | idx_products_brand_deleted | (brand_id, deleted_at) | 브랜드 필터 |
| products | idx_products_deleted_likes | (deleted_at, like_count, created_at) | likes_desc 정렬 (동순위 시 최신순 2차 정렬) |
| products | idx_products_deleted_price | (deleted_at, price) | price_asc 정렬 |
| products | idx_products_deleted_created | (deleted_at, created_at) | latest 정렬 |
| likes | uk_likes_user_product | (user_id, product_id) | 중복 체크 (UK가 인덱스 겸용) |
| likes | idx_likes_user_id | user_id | 내 좋아요 목록 조회 |
| orders | idx_orders_user_created | (user_id, created_at) | 날짜 범위 주문 목록 조회 |
| order_items | idx_order_items_order_id | order_id | 주문 항목 조회 |
| coupon_issues | uk_coupon_issues_coupon_user | (coupon_id, user_id) | 중복 발급 방지 (UK가 인덱스 겸용) |
| coupon_issues | idx_coupon_issues_user_id | user_id | 내 쿠폰 목록 조회 |
| coupon_issues | idx_coupon_issues_coupon_id | coupon_id | 발급 내역 조회 (Admin) |
