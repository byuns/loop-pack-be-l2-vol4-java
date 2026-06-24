package com.loopers.payment.application;

import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderRepository;
import com.loopers.payment.domain.PaymentModel;
import com.loopers.payment.domain.PaymentRepository;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.payment.infrastructure.pg.PgPaymentClient;
import com.loopers.payment.infrastructure.pg.PgPaymentClientDto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentFacade {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PgPaymentClient pgPaymentClient;
    private final PlatformTransactionManager transactionManager;

    @Value("${pg.callback-url}")
    private String callbackUrl;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PaymentInfo requestPayment(Long userId, String loginId, Long orderId, String cardType, String cardNo) {
        // TX1: 비관적 락 획득 → startPayment 커밋 → 커넥션 반환
        OrderModel order = transactionTemplate.execute(status -> {
            OrderModel o = orderRepository.findWithLock(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
            if (!o.getUserId().equals(userId)) {
                throw new CoreException(ErrorType.FORBIDDEN, "접근 권한이 없습니다.");
            }
            o.startPayment();
            return orderRepository.save(o);
        });

        // PG 호출 — 트랜잭션 밖, 커넥션 미점유
        PgPaymentClientDto.TransactionResponse pgResponse;
        try {
            pgResponse = pgPaymentClient.requestPayment(
                loginId,
                new PgPaymentClientDto.PaymentRequest(
                    String.valueOf(orderId),
                    cardType,
                    cardNo,
                    order.getFinalAmount(),
                    callbackUrl
                )
            );
        } catch (Exception pgException) {
            // TX C: PG 실패 → Order를 PAYMENT_FAILED로 전이 (재결제 허용 상태)
            transactionTemplate.execute(status -> {
                OrderModel o = orderRepository.find(orderId)
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
                o.failPayment();
                return orderRepository.save(o);
            });
            throw pgException;
        }

        // TX2: 결제 기록 저장
        return transactionTemplate.execute(status -> {
            PaymentModel payment = new PaymentModel(orderId, pgResponse.transactionKey(), cardType, order.getFinalAmount(), loginId);
            return PaymentInfo.from(paymentRepository.save(payment));
        });
    }

    @Transactional
    public void recoverPayment(Long orderId, Long userId, String loginId) {
        OrderModel order = orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));

        if (!order.getUserId().equals(userId)) {
            throw new CoreException(ErrorType.FORBIDDEN, "접근 권한이 없습니다.");
        }

        Optional<PaymentModel> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isEmpty()) {
            // [fix] 타임아웃 등으로 PaymentModel이 로컬에 저장되지 못한 경우, orderId 기준으로 PG에 실제 거래가 있었는지 확인해 동기화
            recoverFromPgByOrderId(order, loginId);
            return;
        }

        PaymentModel payment = existingPayment.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }

        PgPaymentClientDto.TransactionResponse pgResponse = pgPaymentClient.getTransaction(loginId, payment.getTransactionKey());
        applyPgResult(payment, order, pgResponse.status());
    }

    private void recoverFromPgByOrderId(OrderModel order, String loginId) {
        PgPaymentClientDto.OrderTransactionsResponse pgOrderResponse;
        try {
            pgOrderResponse = pgPaymentClient.getTransactionsByOrder(loginId, order.getId().toString());
        } catch (CoreException e) {
            if (e.getErrorType() == ErrorType.NOT_FOUND) {
                return;
            }
            throw e;
        }

        String latestTransactionKey = pgOrderResponse.transactions().get(0).transactionKey();
        PgPaymentClientDto.TransactionResponse pgResponse = pgPaymentClient.getTransaction(loginId, latestTransactionKey);

        PaymentModel payment = new PaymentModel(order.getId(), latestTransactionKey, "UNKNOWN", order.getFinalAmount(), loginId);
        // [fix] BaseEntity.id 기본값이 0L(null 아님)이라, save() 반환값을 쓰지 않으면 다음 save()도 새 INSERT로 처리되어
        // 같은 transactionKey가 중복 삽입됨 — 반환된(영속화된) 인스턴스로 교체
        payment = paymentRepository.save(payment);
        applyPgResult(payment, order, pgResponse.status());
    }

    @Transactional
    public void handleCallback(String transactionKey) {
        PaymentModel payment = paymentRepository.findByTransactionKey(transactionKey)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }

        OrderModel order = orderRepository.find(payment.getOrderId())
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));

        // [fix] 콜백 발신지를 검증할 방법이 없어, 바디의 status를 그대로 믿는 대신 PG에 재조회한 실제 상태를 사용
        PgPaymentClientDto.TransactionResponse pgResponse = pgPaymentClient.getTransaction(payment.getLoginId(), transactionKey);
        applyPgResult(payment, order, pgResponse.status());
    }

    private void applyPgResult(PaymentModel payment, OrderModel order, String pgStatus) {
        if ("PENDING".equals(pgStatus)) {
            return;
        }

        if ("SUCCESS".equals(pgStatus)) {
            payment.confirm();
            order.confirm();
        } else {
            payment.fail();
            order.failPayment();
        }

        paymentRepository.save(payment);
        orderRepository.save(order);
    }
}
