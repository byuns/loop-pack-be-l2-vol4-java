package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductService productService;
    private final ProductRepository productRepository;

    @Transactional
    public ProductInfo createProduct(String name, String description, Long price, Integer stock) {
        ProductModel product = new ProductModel(name, description, price, stock);
        productRepository.save(product);
        return ProductInfo.from(product);
    }

    @Transactional(readOnly = true)
    public ProductInfo getProduct(Long id) {
        return ProductInfo.from(productService.getOrThrow(productRepository.find(id)));
    }

    @Transactional(readOnly = true)
    public List<ProductInfo> getAllProducts() {
        return productRepository.findAll().stream()
            .map(ProductInfo::from)
            .toList();
    }

    @Transactional
    public ProductInfo updateProduct(Long id, String name, String description, Long price, Integer stock) {
        ProductModel product = productService.getOrThrow(productRepository.find(id));
        product.update(name, description, price, stock);
        return ProductInfo.from(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        ProductModel product = productService.getOrThrow(productRepository.find(id));
        productRepository.delete(product.getId());
    }
}
