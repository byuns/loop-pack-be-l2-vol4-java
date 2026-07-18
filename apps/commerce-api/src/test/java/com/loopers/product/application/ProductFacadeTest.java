package com.loopers.product.application;

import com.loopers.brand.domain.BrandRepository;
import com.loopers.brand.domain.BrandService;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductService;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import com.loopers.stock.domain.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductFacadeTest {

    private ProductCacheService productCacheService;
    private StockRepository stockRepository;
    private ApplicationEventPublisher eventPublisher;
    private RankingRepository rankingRepository;
    private ProductFacade productFacade;

    @BeforeEach
    void setUp() {
        productCacheService = mock(ProductCacheService.class);
        stockRepository = mock(StockRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        rankingRepository = mock(RankingRepository.class);
        productFacade = new ProductFacade(
            mock(ProductService.class),
            mock(ProductRepository.class),
            mock(BrandRepository.class),
            mock(BrandService.class),
            stockRepository,
            productCacheService,
            eventPublisher,
            rankingRepository
        );
    }

    private ProductInfo cached(Long id) {
        return new ProductInfo(id, "에어맥스", "설명", 150000L, 0, null, null, 0L, null);
    }

    @DisplayName("getProduct를 호출할 때,")
    @Nested
    class GetProduct {

        @DisplayName("상품이 순위권에 있으면, 해당 순위를 rank로 채워 반환한다.")
        @Test
        void includesRank_whenProductIsRanked() {
            // arrange
            when(productCacheService.getProductWithoutStock(1L)).thenReturn(cached(1L));
            when(stockRepository.findByProductId(1L)).thenReturn(Optional.empty());
            when(rankingRepository.findRank(RankingKey.daily(LocalDate.now()), 1L)).thenReturn(3L);

            // act
            ProductInfo result = productFacade.getProduct(1L);

            // assert
            assertThat(result.rank()).isEqualTo(3L);
        }

        @DisplayName("상품이 순위권 밖이면, rank는 null이다.")
        @Test
        void rankIsNull_whenProductNotRanked() {
            // arrange
            when(productCacheService.getProductWithoutStock(1L)).thenReturn(cached(1L));
            when(stockRepository.findByProductId(1L)).thenReturn(Optional.empty());
            when(rankingRepository.findRank(RankingKey.daily(LocalDate.now()), 1L)).thenReturn(null);

            // act
            ProductInfo result = productFacade.getProduct(1L);

            // assert
            assertThat(result.rank()).isNull();
        }
    }
}
