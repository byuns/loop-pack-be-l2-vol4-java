package com.loopers.like.interfaces;

import com.loopers.like.application.LikeFacade;
import com.loopers.like.application.LikeInfo;
import com.loopers.product.application.ProductInfo;
import com.loopers.support.auth.CurrentUser;
import com.loopers.support.auth.LoginUser;
import com.loopers.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/likes")
public class LikeV1Controller {

    private final LikeFacade likeFacade;

    @PostMapping
    public ApiResponse<LikeV1Dto.LikeResponse> addLike(
        @CurrentUser LoginUser loginUser,
        @RequestBody LikeV1Dto.AddLikeRequest request
    ) {
        LikeInfo info = likeFacade.addLike(loginUser.id(), request.productId());
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> cancelLike(
        @CurrentUser LoginUser loginUser,
        @PathVariable Long productId
    ) {
        likeFacade.cancelLike(loginUser.id(), productId);
        return ApiResponse.success(null);
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductInfo>> getLikedProducts(@CurrentUser LoginUser loginUser) {
        List<ProductInfo> products = likeFacade.getLikedProducts(loginUser.id());
        return ApiResponse.success(products);
    }
}
