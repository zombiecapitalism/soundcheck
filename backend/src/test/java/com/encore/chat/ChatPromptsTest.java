package com.encore.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 프롬프트 계약 — 도구 결과 밖 생성 금지·가사 인용 금지가 항상 포함돼야 한다. */
class ChatPromptsTest {

    @Test
    void systemPromptContainsConstraintsAndContext() {
        String system = ChatPrompts.system("Megadeth", "2026 부산국제록페스티벌");

        assertThat(system)
                .contains("Megadeth")
                .contains("2026 부산국제록페스티벌")
                .contains("추측하거나 지어내지 않는다")
                .contains("searchDocs")
                .contains("getPredictionStats")
                .contains("가사 원문은 한 소절도 인용하지 않는다");
    }
}
