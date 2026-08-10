package com.encore.prediction;

import com.encore.setlist.Show;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예측 집계 표본 선정 규칙 — 곡 0건 공연(등록만 된 미래 공연, tape뿐인 공연)을 제외하고,
 * 같은 날짜에 여러 세트가 있으면 본 세트 하나만 남긴 뒤 최근 N회를 취한다.
 * 예측 계산과 곡 상세 타임라인이 같은 규칙을 써야
 * "최근 20회 중 19회"라는 근거와 타임라인이 어긋나지 않는다.
 *
 * 같은 날 여러 건 규칙은 채점(AccuracyService)과 동일하다 — 실연주 곡이 가장 많은
 * 세트를 본 세트로 본다(동수면 setlistId로 결정적 선택). 실측: A7X 2026 북미 투어는
 * 본 세트(12곡)와 별칭 오프닝 세트("Statica", 4곡)가 같은 날짜에 공존해, 별칭 세트가
 * 표본 슬롯을 차지하면 본 세트 곡들의 확률이 일괄 희석됐다.
 */
public final class PredictionSampling {

    /** 본 세트 판정 — AccuracyService.matchPastEvents와 같은 비교 기준. */
    private static final Comparator<Show> MAIN_SET = Comparator
            .comparingInt((Show show) -> show.playedSongs().size())
            .thenComparing(Show::getSetlistId);

    private PredictionSampling() {
    }

    /** shows는 최근순 정렬이어야 한다(findAllByArtistMbidWithSongs 반환 순서). */
    public static List<Show> sample(List<Show> recentFirstShows, int limit) {
        Map<LocalDate, Show> mainSetByDate = new LinkedHashMap<>();
        for (Show show : recentFirstShows) {
            if (show.playedSongs().isEmpty()) {
                continue;
            }
            mainSetByDate.merge(show.getEventDate(), show,
                    (a, b) -> MAIN_SET.compare(a, b) >= 0 ? a : b);
        }
        return mainSetByDate.values().stream().limit(limit).toList();
    }
}
