package com.encore.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 곡 배경 설명 생성 — 검색된 근거(top-k)와 출처 목록을 만들고, 본문은 스트리밍한다.
 * 근거가 없으면 LLM을 호출하지 않고 "정보 없음"을 돌려준다(비용·환각 원천 차단).
 * 생성 결과는 DB에 캐시한다 — 같은 곡 재조회는 LLM 없이 즉시 응답하고,
 * 새 문서가 수집되면 아티스트 단위로 무효화된다(RagIngester).
 */
@Service
public class SongExplanationService {

    private static final Logger log = LoggerFactory.getLogger(SongExplanationService.class);

    private final RagRetriever retriever;
    private final SongExplanationCache cache;
    private final ChatClient chatClient;

    public SongExplanationService(RagRetriever retriever, SongExplanationCache cache,
                                  ChatClient.Builder chatClientBuilder) {
        this.retriever = retriever;
        this.cache = cache;
        this.chatClient = chatClientBuilder.build();
    }

    /** 출처 표기용 — 항상 응답에 포함한다(CLAUDE.md 규칙 8). */
    public record Source(String name, String url, String title) {
    }

    /** sources는 즉시 확정, tokens는 구독 시점에 생성이 시작되는 스트림이다. */
    public record Explanation(List<Source> sources, Flux<String> tokens) {
    }

    public Explanation explain(UUID artistMbid, String songKey, String songName, String artistName) {
        Optional<SongExplanationCache.Cached> cached = cache.find(artistMbid, songKey);
        if (cached.isPresent()) {
            return new Explanation(cached.get().sources(), Flux.just(cached.get().content()));
        }

        List<RetrievedChunk> chunks = retriever.retrieve(
                artistMbid, songKey, artistName + " " + songName);
        if (chunks.isEmpty()) {
            // LLM을 부르지 않은 응답은 캐시하지 않는다 — 문서가 수집되면 곧바로 반영되도록
            return new Explanation(List.of(), Flux.just(ExplanationPrompts.NO_INFO));
        }

        List<Source> sources = distinctSources(chunks);
        StringBuilder buffer = new StringBuilder();
        Flux<String> tokens = chatClient.prompt()
                .system(ExplanationPrompts.system())
                .user(ExplanationPrompts.user(artistName, songName, chunks))
                .stream()
                .content()
                .doOnNext(buffer::append)
                // 끝까지 생성된 것만 저장한다 — 중간에 끊긴 스트림은 캐시되지 않는다
                .doOnComplete(() -> saveQuietly(artistMbid, songKey, sources, buffer.toString()));
        return new Explanation(sources, tokens);
    }

    /** 캐시 저장 실패가 이미 전송된 스트림을 error로 바꾸면 안 된다 — 기록만 남긴다. */
    private void saveQuietly(UUID artistMbid, String songKey, List<Source> sources, String content) {
        // 빈 본문이 캐시되면 무효화 전까지 그 곡은 영원히 빈 화면이다 — 저장하지 않는다
        if (content.isBlank()) {
            log.warn("빈 생성 본문은 캐시하지 않음: {} / {}", artistMbid, songKey);
            return;
        }
        try {
            cache.save(artistMbid, songKey, sources, content);
        } catch (RuntimeException e) {
            log.warn("곡 설명 캐시 저장 실패(응답에는 영향 없음): {} / {}", artistMbid, songKey, e);
        }
    }

    /**
     * 프롬프트의 자료 번호 기준(sourceUrlOrder)과 같은 순서로 만든다 —
     * 본문의 [n] 인용이 이 목록의 n번째 출처를 가리킨다.
     */
    private static List<Source> distinctSources(List<RetrievedChunk> chunks) {
        List<Source> sources = new ArrayList<>();
        for (String url : ExplanationPrompts.sourceUrlOrder(chunks)) {
            RetrievedChunk first = chunks.stream()
                    .filter(chunk -> chunk.sourceUrl().equals(url))
                    .findFirst()
                    .orElseThrow();
            sources.add(new Source(first.sourceName(), first.sourceUrl(), first.documentTitle()));
        }
        return sources;
    }
}
