package com.loopers.order.application;

import com.loopers.order.domain.OrderStatus;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.infrastructure.ProductJpaRepository;
import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.stock.domain.StockModel;
import com.loopers.stock.infrastructure.StockJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OrderEntryTokenIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private StockJpaRepository stockJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private ProductModel savedProduct(int totalStock) {
        ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
        stockJpaRepository.save(new StockModel(product.getId(), totalStock));
        return product;
    }

    @DisplayName("입장 토큰과 함께 주문을 생성할 때, ")
    @Nested
    class CreateOrderWithEntryToken {

        @DisplayName("유효한 토큰이면, 주문이 생성되고 토큰이 삭제된다.")
        @Test
        void createsOrder_andDeletesToken_whenTokenIsValid() {
            // arrange
            ProductModel product = savedProduct(100);
            entryTokenRepository.save(new EntryTokenModel(1L, "valid-token"), TTL);

            // act
            OrderInfo info = orderFacade.createOrderWithEntryToken(
                1L, "user1", List.of(new OrderItemCommand(product.getId(), 2)), null, "valid-token"
            );

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PENDING_PAYMENT.name()),
                () -> assertThat(entryTokenRepository.findByUserId(1L)).isEmpty()
            );
        }

        @DisplayName("토큰이 발급되지 않은 유저면, FORBIDDEN 예외가 발생한다.")
        @Test
        void throwsForbidden_whenTokenIsNotIssued() {
            // arrange
            ProductModel product = savedProduct(100);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                orderFacade.createOrderWithEntryToken(
                    1L, "user1", List.of(new OrderItemCommand(product.getId(), 1)), null, "any-token"
                )
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
        }

        @DisplayName("발급된 토큰과 값이 다르면(남의 토큰), FORBIDDEN 예외가 발생하고 기존 토큰은 유지된다.")
        @Test
        void throwsForbidden_andKeepsToken_whenTokenMismatches() {
            // arrange
            ProductModel product = savedProduct(100);
            entryTokenRepository.save(new EntryTokenModel(1L, "my-token"), TTL);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                orderFacade.createOrderWithEntryToken(
                    1L, "user1", List.of(new OrderItemCommand(product.getId(), 1)), null, "others-token"
                )
            );

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN),
                () -> assertThat(entryTokenRepository.findByUserId(1L)).contains("my-token")
            );
        }

        @DisplayName("주문이 실패하면(재고 부족), 토큰이 유지되어 TTL 내 재시도할 수 있다.")
        @Test
        void keepsToken_whenOrderFails() {
            // arrange
            ProductModel product = savedProduct(1);
            entryTokenRepository.save(new EntryTokenModel(1L, "valid-token"), TTL);

            // act
            assertThrows(CoreException.class, () ->
                orderFacade.createOrderWithEntryToken(
                    1L, "user1", List.of(new OrderItemCommand(product.getId(), 5)), null, "valid-token"
                )
            );

            // assert
            assertThat(entryTokenRepository.findByUserId(1L)).contains("valid-token");
        }

        @DisplayName("TTL이 지나 만료된 토큰이면, FORBIDDEN 예외가 발생한다.")
        @Test
        void throwsForbidden_whenTokenIsExpired() throws InterruptedException {
            // arrange
            ProductModel product = savedProduct(100);
            entryTokenRepository.save(new EntryTokenModel(1L, "short-lived"), Duration.ofMillis(100));
            Thread.sleep(300);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                orderFacade.createOrderWithEntryToken(
                    1L, "user1", List.of(new OrderItemCommand(product.getId(), 1)), null, "short-lived"
                )
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
        }
    }
}
