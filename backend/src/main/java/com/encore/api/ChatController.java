package com.encore.api;

import com.encore.chat.ChatRateLimiter;
import com.encore.chat.ChatService;
import com.encore.chat.ChatService.ChatMessage;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.ErrorResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * RAG Chat(E8) — POST + SSE 스트리밍. 곡 설명 SSE와 같은 이벤트 계약이되,
 * 출처는 도구 실행이 끝나야 확정되므로 순서가 다르다: delta* → sources → done.
 * EventSource는 POST를 못 쓰므로 프론트는 fetch 스트림으로 파싱한다.
 */
@RestController
@RequestMapping("/api/events")
public class ChatController {

    public record ChatRequest(List<ChatMessage> messages) {
    }

    private final ChatService chatService;
    private final TargetEventRepository targetEventRepository;
    private final ChatRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, TargetEventRepository targetEventRepository,
                          ChatRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.targetEventRepository = targetEventRepository;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable Long id,
                                              @RequestBody ChatRequest request,
                                              HttpServletRequest httpRequest) {
        TargetEvent event = targetEventRepository.findById(id)
                .orElseThrow(() -> new ApiNotFoundException("존재하지 않는 이벤트입니다: " + id));
        validate(request);
        // 비용 가드: IP·이벤트당 분당 제한 — Chat은 캐시 불가능한 유일한 변동 비용원
        if (!rateLimiter.tryAcquire(clientIp(httpRequest) + ":" + id, System.currentTimeMillis())) {
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS,
                    ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                            "질문이 너무 잦아요. 잠시 후 다시 시도해 주세요."), null);
        }

        ChatService.ChatStream stream = chatService.chat(event, request.messages());
        Flux<ServerSentEvent<String>> deltas = stream.tokens()
                .map(token -> event("delta", objectMapper.writeValueAsString(token)));
        // 출처는 도구 실행이 끝나야 확정 — defer로 스트림 완료 후에 평가한다
        Flux<ServerSentEvent<String>> sources = Flux.defer(() -> Flux.just(
                event("sources", objectMapper.writeValueAsString(stream.sources().get()))));
        Flux<ServerSentEvent<String>> done = Flux.just(event("done", "{}"));

        return Flux.concat(deltas, sources, done)
                .onErrorResume(e -> Flux.just(event("error", objectMapper.writeValueAsString(
                        "답변 생성에 실패했어요. 잠시 후 다시 시도해 주세요."))));
    }

    private static void validate(ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("메시지가 비어 있습니다");
        }
        ChatMessage last = request.messages().getLast();
        if (!"user".equals(last.role()) || last.content() == null || last.content().isBlank()) {
            throw new IllegalArgumentException("마지막 메시지는 비어 있지 않은 user 질문이어야 합니다");
        }
        if (last.content().length() > ChatService.MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException(
                    "질문이 너무 깁니다 (최대 " + ChatService.MAX_QUESTION_CHARS + "자)");
        }
    }

    /** 리버스 프록시(nginx) 뒤에서는 X-Forwarded-For의 첫 IP가 실제 클라이언트다. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
