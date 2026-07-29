package com.encore.prediction;

import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 곡별 연주 확률 계산 — 순수 함수. DB/Spring 없이 공연 목록만으로 계산한다.
 * <p>
 * 공연 i(최근순 0-base)의 가중치 w_i = recencyDecay^i × (show_type이 예측 대상과 같으면 boost).
 * 곡의 확률 = Σ(곡이 등장한 공연의 w_i) / Σ(전체 w_i) — 정의상 0~1을 벗어나지 않는다.
 * tape 곡은 실연주가 아니므로 집계에서 제외한다(Show.playedSongs).
 */
public final class PredictionCalculator {

    public record Params(double recencyDecay, double matchingShowTypeBoost) {
    }

    /** 근거 설명용 등장 기록. eventDate는 직렬화 이슈를 피해 ISO 문자열로 둔다. */
    public record Appearance(String setlistId, String eventDate, double weight, int positionTotal,
                             boolean encore) {
    }

    /** evidence(JSONB)로 저장되는 계산 중간값 — "왜 이 확률인가"를 재구성할 수 있어야 한다. */
    public record Evidence(double recencyDecay, double matchingShowTypeBoost, double weightedScore,
                           double totalWeight, double baseFrequency, List<Appearance> appearances) {
    }

    public record SongScore(String songKey, String songName, BigDecimal probability, int playedCount,
                            int sampleSize, BigDecimal avgPosition, BigDecimal encoreRatio,
                            Evidence evidence) {
    }

    private PredictionCalculator() {
    }

    /** 반환 순서가 곧 순위다: 확률 내림차순 → 연주 횟수 내림차순 → song_key 오름차순(결정적). */
    public static List<SongScore> calculate(List<Show> recentShows, ShowType expectedShowType, Params params) {
        // 호출자 순서를 믿지 않고 최근순으로 직접 정렬한다 — 감쇠 지수가 순서에 달려 있다
        List<Show> shows = recentShows.stream()
                .sorted(Comparator.comparing(Show::getEventDate).reversed()
                        .thenComparing(Show::getSetlistId, Comparator.reverseOrder()))
                .toList();
        int sampleSize = shows.size();
        if (sampleSize == 0) {
            return List.of();
        }

        double totalWeight = 0;
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        for (int i = 0; i < shows.size(); i++) {
            Show show = shows.get(i);
            double weight = Math.pow(params.recencyDecay(), i)
                    * (show.getShowType() == expectedShowType ? params.matchingShowTypeBoost() : 1.0);
            totalWeight += weight;

            // 같은 곡이 한 공연에 두 번 나오면(메들리·리프라이즈) 등장 1회로 세고 첫 위치를 쓴다
            Map<String, ShowSong> firstOccurrence = new LinkedHashMap<>();
            for (ShowSong song : show.playedSongs()) {
                firstOccurrence.putIfAbsent(song.getSongKey(), song);
            }
            for (ShowSong song : firstOccurrence.values()) {
                byKey.computeIfAbsent(song.getSongKey(), key -> new Accumulator(song.getSongName()))
                        .add(show, song, weight);
            }
        }

        List<SongScore> scores = new ArrayList<>(byKey.size());
        for (Map.Entry<String, Accumulator> entry : byKey.entrySet()) {
            Accumulator acc = entry.getValue();
            scores.add(new SongScore(
                    entry.getKey(),
                    acc.songName,
                    toScale(acc.weightedScore / totalWeight, 4),
                    acc.playedCount,
                    sampleSize,
                    toScale((double) acc.positionSum / acc.playedCount, 1),
                    toScale((double) acc.encoreCount / acc.playedCount, 4),
                    new Evidence(params.recencyDecay(), params.matchingShowTypeBoost(),
                            acc.weightedScore, totalWeight,
                            (double) acc.playedCount / sampleSize, List.copyOf(acc.appearances))));
        }
        scores.sort(Comparator.comparing(SongScore::probability).reversed()
                .thenComparing(Comparator.comparingInt(SongScore::playedCount).reversed())
                .thenComparing(SongScore::songKey));
        return scores;
    }

    private static BigDecimal toScale(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static final class Accumulator {
        /** 최근 공연부터 순회하므로 처음 만난 표기가 가장 최신 원본 곡명이다. */
        private final String songName;
        private double weightedScore;
        private int playedCount;
        private int positionSum;
        private int encoreCount;
        private final List<Appearance> appearances = new ArrayList<>();

        private Accumulator(String songName) {
            this.songName = songName;
        }

        private void add(Show show, ShowSong song, double weight) {
            weightedScore += weight;
            playedCount++;
            positionSum += song.getPositionTotal();
            if (song.isEncore()) {
                encoreCount++;
            }
            appearances.add(new Appearance(show.getSetlistId(), show.getEventDate().toString(),
                    weight, song.getPositionTotal(), song.isEncore()));
        }
    }
}
