package com.loopers.queue.interfaces;

import com.loopers.support.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Queue V1 API", description = "대기열 API")
public interface QueueV1ApiSpec {

    @Operation(summary = "대기열 진입", description = "대기열에 진입하고 순번을 반환한다.")
    ApiResponse<QueueV1Dto.WaitingResponse> enter(QueueV1Dto.EnterRequest request);

    @Operation(summary = "순번 조회", description = "현재 유저의 대기 순번을 조회한다.")
    ApiResponse<QueueV1Dto.WaitingResponse> getPosition(Long userId);

    @Operation(summary = "전체 대기 인원 조회", description = "현재 대기 중인 전체 인원을 반환한다.")
    ApiResponse<QueueV1Dto.SizeResponse> getSize();
}
