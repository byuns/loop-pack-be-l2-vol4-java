package com.loopers.queue.interfaces;

import com.loopers.queue.application.QueueFacade;
import com.loopers.queue.application.WaitingInfo;
import com.loopers.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/queue")
public class QueueV1Controller implements QueueV1ApiSpec {

    private final QueueFacade queueFacade;

    @PostMapping("/enter")
    @Override
    public ApiResponse<QueueV1Dto.WaitingResponse> enter(@RequestBody QueueV1Dto.EnterRequest request) {
        WaitingInfo info = queueFacade.enter(request.userId());
        return ApiResponse.success(QueueV1Dto.WaitingResponse.from(info));
    }

    @GetMapping("/position")
    @Override
    public ApiResponse<QueueV1Dto.WaitingResponse> getPosition(@RequestParam("userId") Long userId) {
        WaitingInfo info = queueFacade.getPosition(userId);
        return ApiResponse.success(QueueV1Dto.WaitingResponse.from(info));
    }

    @GetMapping("/size")
    @Override
    public ApiResponse<QueueV1Dto.SizeResponse> getSize() {
        return ApiResponse.success(QueueV1Dto.SizeResponse.of(queueFacade.getSize()));
    }

    // Polling과 병행. 스케줄러 배치 처리 후 서버가 대기자에게 상태를 push한다.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("userId") Long userId) {
        return queueFacade.subscribe(userId);
    }
}
