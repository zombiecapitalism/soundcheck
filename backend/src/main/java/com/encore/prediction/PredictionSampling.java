package com.encore.prediction;

import com.encore.setlist.Show;

import java.util.List;

/**
 * 예측 집계 표본 선정 규칙 — 곡 0건 공연(등록만 된 미래 공연, tape뿐인 공연)을 제외하고
 * 최근 N회를 취한다. 예측 계산과 곡 상세 타임라인이 같은 규칙을 써야
 * "최근 20회 중 19회"라는 근거와 타임라인이 어긋나지 않는다.
 */
public final class PredictionSampling {

    private PredictionSampling() {
    }

    /** shows는 최근순 정렬이어야 한다(findAllByArtistMbidWithSongs 반환 순서). */
    public static List<Show> sample(List<Show> recentFirstShows, int limit) {
        return recentFirstShows.stream()
                .filter(show -> !show.playedSongs().isEmpty())
                .limit(limit)
                .toList();
    }
}
