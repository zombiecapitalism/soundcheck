package com.encore.prediction;

import com.encore.prediction.TrendChanges.Changes;
import com.encore.prediction.TrendChanges.SongChange;

import java.util.List;

/**
 * 변화 요약 프롬프트(E4) — 순수 함수. 제약(통계에 없는 내용 생성 금지)이
 * 항상 포함되는지 단위 테스트한다. ExplanationPrompts와 같은 패턴.
 */
public final class TrendSummaryPrompts {

    private TrendSummaryPrompts() {
    }

    public static String system() {
        return """
                너는 공연 예습 서비스의 셋리스트 변화 요약가다. 반드시 지켜야 할 규칙:
                - 아래 사용자 메시지에 제공된 통계만 근거로 답한다. 통계에 없는 사실(발매 정보, 투어 사정 등)은 절대 추측하거나 지어내지 않는다.
                - 한국어 2~3문장. 팬에게 "요즘 셋리스트가 어떻게 움직이는지"를 알려주는 톤.
                - 곡명은 제공된 표기 그대로 쓴다. 수치를 언급할 때는 제공된 수치만 쓴다.
                """;
    }

    public static String user(String artistName, Changes changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("아티스트: ").append(artistName).append("\n\n최근 셋리스트 변화 통계:\n");
        appendGroup(sb, "신규 진입(모든 등장이 최근 5회 안)", changes.newcomers());
        appendGroup(sb, "상승세(표본 최근 절반에서 등장률 증가)", changes.rising());
        appendGroup(sb, "이탈(자주 하던 곡이 최근 5회 미등장)", changes.dropped());
        appendGroup(sb, "하락세(표본 최근 절반에서 등장률 감소)", changes.falling());
        sb.append("\n위 통계만 근거로 셋리스트 변화를 2~3문장으로 요약하라.");
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String title, List<SongChange> group) {
        if (group.isEmpty()) {
            return;
        }
        sb.append("- ").append(title).append(":\n");
        for (SongChange change : group) {
            sb.append("  · ").append(change.songName())
                    .append(" — 최근 ").append(change.sampleSize()).append("회 중 ")
                    .append(change.playedCount()).append("회, 최근 5회 중 ")
                    .append(change.recentCount5()).append("회\n");
        }
    }
}
