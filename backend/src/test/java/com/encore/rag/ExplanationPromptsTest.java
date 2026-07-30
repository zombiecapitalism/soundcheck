package com.encore.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationPromptsTest {

    private static RetrievedChunk chunk(String content, String title, String url) {
        return new RetrievedChunk(content, title, "Wikipedia", url, 0.8);
    }

    /** CLAUDE.md 규칙 8·9 — 이 제약이 프롬프트에서 빠지면 안 된다. */
    @Test
    void systemPromptContainsAllCoreConstraints() {
        String system = ExplanationPrompts.system();

        assertThat(system).contains("자료에 없는 사실은 절대 추측하거나 지어내지 않는다");
        assertThat(system).contains(ExplanationPrompts.NO_INFO);
        assertThat(system).contains("가사 원문은 한 소절도 인용하지 않는다");
    }

    @Test
    void referenceNumbersArePerSourceNotPerChunk() {
        // 같은 문서(url-a)의 청크 둘 + 다른 문서(url-b) 하나
        List<RetrievedChunk> chunks = List.of(
                chunk("첫 청크", "Afterlife (song)", "url-a"),
                chunk("둘째 청크", "Afterlife (song)", "url-a"),
                chunk("앨범 청크", "Avenged Sevenfold (album)", "url-b"));

        String user = ExplanationPrompts.user("Avenged Sevenfold", "Afterlife", chunks);

        // url-a 청크 둘은 모두 [1], url-b는 [2] — 응답 출처 목록 순서와 일치해야 한다
        assertThat(user).containsSubsequence("[1] (Afterlife (song)", "첫 청크",
                "[1] (Afterlife (song)", "둘째 청크", "[2] (Avenged Sevenfold (album)", "앨범 청크");
        assertThat(user).contains("아티스트: Avenged Sevenfold");
        assertThat(user).contains("곡: Afterlife");
    }

    @Test
    void sourceOrderIsFirstAppearanceDeduplicated() {
        List<RetrievedChunk> chunks = List.of(
                chunk("a", "t1", "url-1"),
                chunk("b", "t2", "url-2"),
                chunk("c", "t1", "url-1"));

        assertThat(ExplanationPrompts.sourceUrlOrder(chunks)).containsExactly("url-1", "url-2");
    }
}
