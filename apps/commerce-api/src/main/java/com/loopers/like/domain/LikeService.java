package com.loopers.like.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LikeService {

    public LikeModel getOrThrow(Optional<LikeModel> like) {
        return like.orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "좋아요가 존재하지 않습니다."));
    }
}
