package com.encore.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 곡 배경 설명 생성 — 검색된 근거(top-k)와 출처 목록을 만들고, 본문은 스트리밍한다.
 * 근거가 없으면 LLM을 호출하지 않고 "정보 없음"을 돌려준다(비용·환각 원천 차단).
 */
@Service
public class SongExplanationService {

    private final RagRetriever retriever;
    private final ChatClient chatClient;

    public SongExplanationService(RagRetriever retriever, ChatClient.Builder chatClientBuilder) {
        this.retriever = retriever;
        this.chatClient = chatClientBuilder.build();
    }

    /** 출처 표기용 — 항상 응답에 포함한다(CLAUDE.md 규칙 8). */
    public record Source(String name, String url, String title) {
    }

    /** sources는 즉시 확정, tokens는 구독 시점에 생성이 시작되는 스트림이다. */
    public record Explanation(List<Source> sources, Flux<String> tokens) {
    }

    public Explanation explain(UUID artistMbid, String songKey, String songName, String artistName) {
        List<RetrievedChunk> chunks = retriever.retrieve(
                artistMbid, songKey, artistName + " " + songName);
        if (chunks.isEmpty()) {
            return new Explanation(List.of(), Flux.just(ExplanationPrompts.NO_INFO));
        }
        return new Explanation(distinctSources(chunks), chatClient.prompt()
                .system(ExplanationPrompts.system())
                .user(ExplanationPrompts.user(artistName, songName, chunks))
                .stream()
                .content());
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
