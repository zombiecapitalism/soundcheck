package com.encore.prediction;

import com.encore.prediction.PredictionCalculator.Evidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** evidence(JSONB) 역직렬화 — 구버전 스냅샷과 손상 데이터에 대한 관용이 핵심이다. */
class EvidenceJsonTest {

    /** v0.2 이전에 저장된 evidence: 확장 필드가 없어도 기존 필드는 읽히고 새 필드는 null이다. */
    @Test
    void readsLegacyEvidenceWithNullExtensions() {
        String legacy = """
                {"recencyDecay":0.95,"matchingShowTypeBoost":1.5,"weightedScore":12.3,
                 "totalWeight":15.6,"baseFrequency":0.95,
                 "appearances":[{"setlistId":"abc","eventDate":"2026-07-01","weight":2.0,
                                 "positionTotal":3,"encore":false}]}""";

        Evidence evidence = EvidenceJson.parse(legacy);

        assertThat(evidence).isNotNull();
        assertThat(evidence.baseFrequency()).isEqualTo(0.95);
        assertThat(evidence.appearances()).hasSize(1);
        assertThat(evidence.unboostedProbability()).isNull();
        assertThat(evidence.recentCount5()).isNull();
        assertThat(evidence.trend()).isNull();
        assertThat(evidence.positionStats()).isNull();
        assertThat(evidence.typeBreakdown()).isNull();
    }

    /** 손상된 한 행이 목록 응답 전체를 죽이면 안 된다 — null로 강등. */
    @Test
    void toleratesMissingOrBrokenJson() {
        assertThat(EvidenceJson.parse(null)).isNull();
        assertThat(EvidenceJson.parse("  ")).isNull();
        assertThat(EvidenceJson.parse("{broken")).isNull();
    }

    /** 직렬화-역직렬화 왕복: PredictionGenerator가 쓰는 것과 같은 record라 스키마가 어긋날 수 없다. */
    @Test
    void roundTripsCurrentEvidence() {
        Evidence original = new Evidence(0.95, 1.5, 12.3, 15.6, 0.95,
                0.9, 4, PredictionCalculator.Trend.RISING,
                new PredictionCalculator.PositionStats(1, 2, 3, 4, 5),
                new PredictionCalculator.TypeBreakdown(10, 8, 5, 2),
                java.util.List.of());
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(original);

        assertThat(EvidenceJson.parse(json)).isEqualTo(original);
    }
}
