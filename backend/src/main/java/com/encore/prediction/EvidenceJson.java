package com.encore.prediction;

import com.encore.prediction.PredictionCalculator.Evidence;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * prediction.evidence(JSONB) 문자열 → Evidence 역직렬화.
 * 직렬화는 PredictionGenerator가 같은 record로 하므로 스키마가 어긋날 수 없다.
 * v0.2 이전에 저장된 evidence는 확장 필드가 없어 해당 필드만 null로 읽힌다.
 */
public final class EvidenceJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EvidenceJson() {
    }

    /** 파싱 실패는 근거 표시만 포기(null)한다 — 손상된 한 행이 응답 전체를 죽이면 안 된다. */
    public static Evidence parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Evidence.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}
