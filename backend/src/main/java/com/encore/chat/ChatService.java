package com.encore.chat;

import com.encore.llm.LlmCallRecorder;
import com.encore.llm.LlmCallType;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.rag.RagRetriever;
import com.encore.rag.SongExplanationService.Source;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * RAG Chat(E8) — tool calling 스트리밍. 대화 이력은 클라이언트가 함께 보내는
 * stateless 방식(서버 세션 없음)이며 서버가 상한을 강제한다.
 */
@Service
public class ChatService {

    /** 최근 6턴(질문+답 12메시지) 상한 — 프롬프트 비용과 길이를 묶는다. */
    public static final int MAX_MESSAGES = 12;
    public static final int MAX_QUESTION_CHARS = 500;
    /** 이력 메시지 1건 상한 — 질문만 제한하면 앞 이력을 수 MB로 채워 토큰 비용이 뚫린다. */
    public static final int MAX_HISTORY_MESSAGE_CHARS = 2000;

    public record ChatMessage(String role, String content) {
    }

    /** tokens 스트림이 끝난 뒤 sources()가 확정된다(도구 실행 결과). */
    public record ChatStream(Flux<String> tokens, Supplier<List<Source>> sources) {
    }

    private final ChatClient chatClient;
    private final RagRetriever retriever;
    private final PredictionRepository predictionRepository;
    private final LlmCallRecorder llmCallRecorder;

    public ChatService(ChatClient.Builder chatClientBuilder, RagRetriever retriever,
                       PredictionRepository predictionRepository, LlmCallRecorder llmCallRecorder) {
        this.chatClient = chatClientBuilder.build();
        this.retriever = retriever;
        this.predictionRepository = predictionRepository;
        this.llmCallRecorder = llmCallRecorder;
    }

    /** messages의 마지막은 user 질문이어야 한다(컨트롤러가 검증). */
    public ChatStream chat(TargetEvent event, List<ChatMessage> messages) {
        ChatTools tools = new ChatTools(retriever, predictionRepository,
                event.getArtist().getMbid(), event.getId());

        List<ChatMessage> bounded = messages.size() > MAX_MESSAGES
                ? messages.subList(messages.size() - MAX_MESSAGES, messages.size())
                : messages;
        List<Message> history = new ArrayList<>(bounded.size());
        for (ChatMessage message : bounded) {
            history.add("assistant".equals(message.role())
                    ? new AssistantMessage(message.content())
                    : new UserMessage(message.content()));
        }

        long start = System.currentTimeMillis();
        // 완료·오류·취소 중 정확히 한 번만 기록한다 — 클라이언트가 스트림을 끊어도(취소)
        // 토큰 비용은 이미 발생했으므로 계측에서 빠지면 안 된다(E9)
        AtomicBoolean recorded = new AtomicBoolean();
        Flux<String> tokens = chatClient.prompt()
                .system(ChatPrompts.system(event.getArtist().getName(), event.getEventName()))
                .messages(history)
                .tools(tools)
                .stream()
                .content()
                // 스트리밍이라 usage가 없다 — 지연·성공 여부만 계측(E9). 캐시 불가 유형이라 전 호출 기록
                .doOnError(e -> {
                    if (recorded.compareAndSet(false, true)) {
                        llmCallRecorder.recordError(LlmCallType.CHAT, null,
                                System.currentTimeMillis() - start, e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        llmCallRecorder.record(LlmCallType.CHAT, null, null, null,
                                System.currentTimeMillis() - start, false, null);
                    }
                })
                .doOnCancel(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        llmCallRecorder.recordCancelled(LlmCallType.CHAT,
                                System.currentTimeMillis() - start);
                    }
                });
        return new ChatStream(tokens, tools::usedSources);
    }
}
