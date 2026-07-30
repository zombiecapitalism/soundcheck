package com.encore.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 곡 설명 생성 프롬프트 — 순수 함수로 분리해 제약(근거 없는 내용 금지, 정보 없음,
 * 가사 인용 금지)이 항상 포함되는지 단위 테스트한다.
 * <p>
 * 자료 번호는 청크가 아니라 <b>출처(문서 URL)</b> 단위다 — 같은 문서의 청크 여럿이
 * 잡혀도 같은 번호를 달아, 모델의 [n] 인용이 응답의 출처 목록 순서와 정확히 일치한다.
 */
public final class ExplanationPrompts {

    /** 근거가 부족할 때 모델이 내야 하는 정확한 응답 — 프론트도 이 문자열로 빈 상태를 판별한다. */
    public static final String NO_INFO = "정보 없음";

    private ExplanationPrompts() {
    }

    public static String system() {
        return """
                너는 공연 예습 서비스의 곡 해설가다. 반드시 지켜야 할 규칙:
                - 아래 사용자 메시지에 제공된 자료의 내용만 근거로 답한다. 자료에 없는 사실은 절대 추측하거나 지어내지 않는다.
                - 자료에 해당 곡을 설명할 근거가 부족하면 다른 말 없이 정확히 "%s"이라고만 답한다.
                - 가사 원문은 한 소절도 인용하지 않는다. 곡의 의미·배경·해석만 서술한다.
                - 한국어로 3~6문장. 곡의 의미와 배경 → 앨범 맥락 → 라이브에서의 특징 순으로, 있는 것만 서술한다.
                - 근거로 쓴 자료 번호를 문장 끝에 [1]처럼 표시한다.
                """.formatted(NO_INFO);
    }

    /** 출처 URL의 등장 순서(중복 제거) — 자료 번호와 출처 목록이 공유하는 기준. */
    public static List<String> sourceUrlOrder(List<RetrievedChunk> chunks) {
        List<String> urls = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (!urls.contains(chunk.sourceUrl())) {
                urls.add(chunk.sourceUrl());
            }
        }
        return urls;
    }

    public static String user(String artistName, String songName, List<RetrievedChunk> chunks) {
        List<String> urlOrder = sourceUrlOrder(chunks);
        StringBuilder sb = new StringBuilder();
        sb.append("아티스트: ").append(artistName).append('\n');
        sb.append("곡: ").append(songName).append("\n\n자료:\n");
        for (RetrievedChunk chunk : chunks) {
            int sourceNumber = urlOrder.indexOf(chunk.sourceUrl()) + 1;
            sb.append('[').append(sourceNumber).append("] (")
                    .append(chunk.documentTitle()).append(" — ").append(chunk.sourceName())
                    .append(")\n").append(chunk.content()).append("\n\n");
        }
        sb.append("위 자료만 근거로 '").append(songName).append("'을(를) 설명하라.");
        return sb.toString();
    }
}
