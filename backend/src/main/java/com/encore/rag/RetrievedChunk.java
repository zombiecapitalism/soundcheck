package com.encore.rag;

/** 유사도 검색 결과 청크 — 생성 프롬프트의 근거 단위. 출처는 항상 함께 다닌다. */
public record RetrievedChunk(
        String content,
        String documentTitle,
        String sourceName,
        String sourceUrl,
        double score
) {
}
