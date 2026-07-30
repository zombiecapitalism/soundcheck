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
        // 프롬프트가 아티스트 "이름"을 쓴다 — 트랜잭션 밖 지연 로딩이 안 되므로 fetch join 필수
        TargetEvent event = targetEventRepository.findByIdWithArtist(id)
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
        // 공개 API — 이력 중간의 null content/이상한 role이 그대로 모델 메시지로 가면 NPE 500이 된다
        for (ChatMessage message : request.messages()) {
            if (message == null || message.content() == null || message.content().isBlank()
                    || !("user".equals(message.role()) || "assistant".equals(message.role()))) {
                throw new IllegalArgumentException(
                        "메시지는 user/assistant role과 비어 있지 않은 content가 필요합니다");
            }
            // 질문(500자)만 제한하면 앞 이력을 거대하게 채워 토큰 비용 가드가 뚫린다
            if (message.content().length() > ChatService.MAX_HISTORY_MESSAGE_CHARS) {
                throw new IllegalArgumentException("이력 메시지가 너무 깁니다 (메시지당 최대 "
                        + ChatService.MAX_HISTORY_MESSAGE_CHARS + "자)");
            }
        }
        ChatMessage last = request.messages().getLast();
        if (!"user".equals(last.role())) {
            throw new IllegalArgumentException("마지막 메시지는 user 질문이어야 합니다");
        }
        if (last.content().length() > ChatService.MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException(
                    "질문이 너무 깁니다 (최대 " + ChatService.MAX_QUESTION_CHARS + "자)");
        }
    }

    /**
     * 레이트리밋 키용 클라이언트 IP. X-Forwarded-For의 **마지막** 항목을 쓴다 —
     * 첫 항목은 클라이언트가 임의 헤더로 위조해 제한을 우회할 수 있고(append 방식 프록시),
     * 마지막 항목은 우리 앞의 신뢰 프록시(nginx) 1홉이 직접 본 주소다.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].strip();
        }
        return request.getRemoteAddr();
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
