package com.encore.rag;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;

/**
 * 문서 본문 → 500~800토큰 청크(PRD 8장). 순수 함수 — 컴포넌트와 분리해 단위 테스트한다.
 * 분할과 토큰 수 집계 모두 cl100k 인코딩 기준이라 서로 어긋나지 않는다.
 */
public final class RagChunker {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /** 목표보다 짧은 꼬리 청크가 노이즈가 되지 않게 하는 최소 크기(문자). */
    private static final int MIN_CHUNK_SIZE_CHARS = 350;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int MAX_NUM_CHUNKS = 10_000;

    private RagChunker() {
    }

    public record Chunk(String content, int tokenCount) {
    }

    /** targetTokens가 상한이다 — 모든 청크는 targetTokens 이하의 토큰을 가진다. */
    public static List<Chunk> chunk(String text, int targetTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(targetTokens)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
                .withMaxNumChunks(MAX_NUM_CHUNKS)
                .withKeepSeparator(true)
                .build();
        return splitter.apply(List.of(new Document(text))).stream()
                .map(Document::getText)
                .map(content -> new Chunk(content, ENCODING.countTokens(content)))
                .toList();
    }
}
