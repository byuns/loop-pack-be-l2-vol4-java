package com.loopers.like.application;

import com.loopers.like.domain.event.LikeAddedEvent;
import com.loopers.like.domain.event.LikeCancelledEvent;
import com.loopers.product.domain.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LikeEventListenerTest {

    private ProductRepository productRepository;
    private LikeEventListener likeEventListener;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        likeEventListener = new LikeEventListener(productRepository);
    }

    @DisplayName("LikeAddedEvent를 수신할 때,")
    @Nested
    class HandleLikeAdded {

        @DisplayName("productRepository.incrementLikeCount를 호출한다.")
        @Test
        void callsIncrementLikeCount_whenLikeAddedEventReceived() {
            // arrange
            LikeAddedEvent event = new LikeAddedEvent(1L);

            // act
            likeEventListener.handleLikeAdded(event);

            // assert
            verify(productRepository).incrementLikeCount(1L);
        }
    }

    @DisplayName("LikeCancelledEvent를 수신할 때,")
    @Nested
    class HandleLikeCancelled {

        @DisplayName("productRepository.decrementLikeCount를 호출한다.")
        @Test
        void callsDecrementLikeCount_whenLikeCancelledEventReceived() {
            // arrange
            LikeCancelledEvent event = new LikeCancelledEvent(1L);

            // act
            likeEventListener.handleLikeCancelled(event);

            // assert
            verify(productRepository).decrementLikeCount(1L);
        }
    }
}
