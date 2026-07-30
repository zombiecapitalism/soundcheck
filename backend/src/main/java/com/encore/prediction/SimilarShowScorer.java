package com.encore.prediction;

import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 유사 공연 점수화(E11) — 순수 함수. "이번 공연은 이 공연들과 비슷할 것"의 근거를 만든다.
 * 점수 = 유형 일치(0.4) + 시기 근접(0.3, 반감기 감쇠) + 곡 구성 겹침(0.3, 예측 상위 K와 Jaccard).
 */
public final class SimilarShowScorer {

    static final double TYPE_WEIGHT = 0.4;
    static final double RECENCY_WEIGHT = 0.3;
    static final double OVERLAP_WEIGHT = 0.3;
    /** 시기 점수 반감기 — 1년 전 공연이면 0.5, 2년 전이면 0.25. */
    static final double RECENCY_HALF_LIFE_DAYS = 365.0;

    public record ScoredShow(Show show, double score, boolean typeMatch,
                             double recencyScore, double overlapScore, int overlapCount) {
    }

    private SimilarShowScorer() {
    }

    /**
     * pastShows에서 상위 limit개. 곡 0건 공연은 비교할 셋이 없어 제외한다.
     * predictedTopKeys는 예측 상위 K곡의 song_key 집합(겹침의 기준).
     */
    public static List<ScoredShow> topSimilar(List<Show> pastShows, ShowType expectedType,
                                              LocalDate eventDate, Set<String> predictedTopKeys,
                                              int limit) {
        return pastShows.stream()
                .filter(show -> !show.playedSongs().isEmpty())
                .map(show -> score(show, expectedType, eventDate, predictedTopKeys))
                .sorted(Comparator.comparingDouble(ScoredShow::score).reversed()
                        // 동점이면 최근 공연 우선, 그다음 setlistId — 결정적 정렬
                        .thenComparing(scored -> scored.show().getEventDate(), Comparator.reverseOrder())
                        .thenComparing(scored -> scored.show().getSetlistId()))
                .limit(limit)
                .toList();
    }

    private static ScoredShow score(Show show, ShowType expectedType, LocalDate eventDate,
                                    Set<String> predictedTopKeys) {
        boolean typeMatch = show.getShowType() == expectedType;

        long days = Math.abs(ChronoUnit.DAYS.between(show.getEventDate(), eventDate));
        double recencyScore = Math.pow(0.5, days / RECENCY_HALF_LIFE_DAYS);

        Set<String> played = new HashSet<>();
        for (ShowSong song : show.playedSongs()) {
            played.add(song.getSongKey());
        }
        int overlapCount = 0;
        for (String key : predictedTopKeys) {
            if (played.contains(key)) {
                overlapCount++;
            }
        }
        int unionSize = played.size() + predictedTopKeys.size() - overlapCount;
        double overlapScore = unionSize == 0 ? 0 : (double) overlapCount / unionSize;

        double score = (typeMatch ? TYPE_WEIGHT : 0)
                + RECENCY_WEIGHT * recencyScore
                + OVERLAP_WEIGHT * overlapScore;
        return new ScoredShow(show, score, typeMatch, recencyScore, overlapScore, overlapCount);
    }
}
