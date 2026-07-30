package com.encore.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkerTest {

    @Test
    void everyChunkStaysWithinTargetTokens() {
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(2000);

        List<RagChunker.Chunk> chunks = RagChunker.chunk(text, 650);

        assertThat(chunks).isNotEmpty().hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(650);
            assertThat(chunk.tokenCount()).isPositive();
            assertThat(chunk.content()).isNotBlank();
        });
    }

    @Test
    void shortTextBecomesSingleChunk() {
        List<RagChunker.Chunk> chunks = RagChunker.chunk("A short paragraph about a song.", 650);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("short paragraph");
    }

    @Test
    void blankTextBecomesEmptyList() {
        assertThat(RagChunker.chunk("", 650)).isEmpty();
        assertThat(RagChunker.chunk("   ", 650)).isEmpty();
        assertThat(RagChunker.chunk(null, 650)).isEmpty();
    }

    @Test
    void splitsKoreanTextByTokens() {
        String text = "이 곡은 밴드의 대표곡이다. 라이브에서 자주 연주된다. ".repeat(1000);

        List<RagChunker.Chunk> chunks = RagChunker.chunk(text, 650);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isLessThanOrEqualTo(650));
    }
}
