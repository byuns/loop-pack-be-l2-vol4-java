package com.loopers.ranking.application;

import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductRepository;
import com.loopers.ranking.domain.RankedEntry;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class RankingFacade {

    private final RankingRepository rankingRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<RankingInfo> getRankings(LocalDate date, int page, int size) {
        int offset = (page - 1) * size;
        List<RankedEntry> entries = rankingRepository.findPage(RankingKey.daily(date), offset, size);
        if (entries.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = entries.stream().map(RankedEntry::productId).toList();
        Map<Long, ProductModel> products = productRepository.findAllByIds(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        List<RankingInfo> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            RankedEntry entry = entries.get(i);
            ProductModel product = products.get(entry.productId());
            // 랭킹엔 있으나 상품이 삭제된 경우는 건너뛴다. rank는 ZSET 위치(offset+i+1)를 그대로 쓴다.
            if (product == null) {
                continue;
            }
            result.add(new RankingInfo(
                offset + i + 1L,
                product.getId(),
                product.getName(),
                product.getPrice(),
                entry.score()
            ));
        }
        return result;
    }
}
