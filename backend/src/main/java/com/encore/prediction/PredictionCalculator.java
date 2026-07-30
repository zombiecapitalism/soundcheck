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

    /** 최근 절반 vs 이전 절반 등장률 차가 이 값 이상이면 RISING/FALLING. */
    public static final double TREND_THRESHOLD = 0.2;

    public record Params(double recencyDecay, double matchingShowTypeBoost) {
        /**
         * 운영 경로는 PredictionProperties 검증이 막아주지만 순수 함수 단독 사용도 안전해야 한다.
         * boost=0이면 유형 일치 공연의 가중치가 전부 0이 되어 totalWeight=0 → 0/0=NaN으로 터진다.
         */
        public Params {
            if (recencyDecay <= 0 || recencyDecay > 1) {
                throw new IllegalArgumentException("recencyDecay는 (0, 1] 범위여야 합니다: " + recencyDecay);
            }
            if (matchingShowTypeBoost <= 0) {
                throw new IllegalArgumentException(
                        "matchingShowTypeBoost는 양수여야 합니다: " + matchingShowTypeBoost);
            }
        }
    }

    /** 표본 최근 절반 vs 이전 절반의 등장률 변화. */
    public enum Trend { RISING, STABLE, FALLING }

    /** 근거 설명용 등장 기록. eventDate는 직렬화 이슈를 피해 ISO 문자열로 둔다. */
    public record Appearance(String setlistId, String eventDate, double weight, int positionTotal,
                             boolean encore) {
    }

    /**
     * 셋리스트 내 위치 구간 등장 횟수. opener는 본편 1번째, early/mid/late는 나머지 본편의
     * 3분위(앙코르 제외 곡 수 기준), encore는 앙코르 블록. 합 = playedCount.
     */
    public record PositionStats(int opener, int early, int mid, int late, int encore) {
    }

    /** 표본 내 show_type별 공연 수와 등장 횟수 — UNKNOWN은 어느 쪽에도 넣지 않는다. */
    public record TypeBreakdown(int festivalShows, int festivalPlayed, int soloShows, int soloPlayed) {
    }

    /**
     * evidence(JSONB)로 저장되는 계산 중간값 — "왜 이 확률인가"를 재구성할 수 있어야 한다.
     * unboostedProbability 이하 필드는 v0.2 확장분이라 참조 타입이다 —
     * 구버전 evidence를 역직렬화하면 null로 읽히고, 응답도 그대로 null을 내보낸다(재계산 시 채워짐).
     */
    public record Evidence(double recencyDecay, double matchingShowTypeBoost, double weightedScore,
                           double totalWeight, double baseFrequency,
                           Double unboostedProbability, Integer recentCount5, Trend trend,
                           PositionStats positionStats, TypeBreakdown typeBreakdown,
                           List<Appearance> appearances) {
    }

    public record SongScore(String songKey, String songName, BigDecimal probability, int playedCount,
                            int sampleSize, BigDecimal avgPosition, BigDecimal encoreRatio,
                            Evidence evidence) {
    }

    private PredictionCalculator() {
    }

    /** 반환 순서가 곧 순위다: 확률 내림차순 → 연주 횟수 내림차순 → song_key 오름차순(결정적). */
    public static List<SongScore> calculate(List<Show> recentShows, ShowType expectedShowType, Params params) {
        // 곡 0건(등록만 된 미래 공연, tape뿐인 공연)은 집계에서 제외한다 — docs/setlist-schema.md 1.4.
        // 빈 공연은 최근순 맨 앞에 오기 쉬워서, 포함하면 가장 높은 가중치 슬롯이 낭비되고
        // 모든 곡의 확률이 일괄 희석된다.
        // 호출자 순서를 믿지 않고 최근순으로 직접 정렬한다 — 감쇠 지수가 순서에 달려 있다.
        List<Show> shows = recentShows.stream()
                .filter(show -> !show.playedSongs().isEmpty())
                .sorted(Comparator.comparing(Show::getEventDate).reversed()
                        .thenComparing(Show::getSetlistId, Comparator.reverseOrder()))
                .toList();
        int sampleSize = shows.size();
        if (sampleSize == 0) {
            return List.of();
        }
        // 추이 분할: 인덱스 < halfBoundary가 최근 절반. 표본이 홀수면 이전 절반이 1회 더 크다.
        int halfBoundary = sampleSize / 2;

        double totalWeight = 0;
        double unboostedTotalWeight = 0;
        int festivalShows = 0;
        int soloShows = 0;
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        for (int i = 0; i < shows.size(); i++) {
            Show show = shows.get(i);
            double decayWeight = Math.pow(params.recencyDecay(), i);
            double weight = decayWeight
                    * (show.getShowType() == expectedShowType ? params.matchingShowTypeBoost() : 1.0);
            totalWeight += weight;
            unboostedTotalWeight += decayWeight;
            if (show.getShowType() == ShowType.FESTIVAL) {
                festivalShows++;
            } else if (show.getShowType() == ShowType.SOLO) {
                soloShows++;
            }

            // 같은 곡이 한 공연에 두 번 나오면(메들리·리프라이즈) 등장 1회로 세고 첫 위치를 쓴다
            Map<String, ShowSong> firstOccurrence = new LinkedHashMap<>();
            for (ShowSong song : show.playedSongs()) {
                firstOccurrence.putIfAbsent(song.getSongKey(), song);
            }
            // 위치 구간용: 본편(앙코르 제외) 등장 순서. positionTotal은 tape 곡을 포함해 매겨질 수
            // 있어 절대값 대신 실연주 본편 내 순번으로 3분위를 나눈다.
            Map<String, Integer> mainIndexByKey = new LinkedHashMap<>();
            for (ShowSong song : firstOccurrence.values()) {
                if (!song.isEncore()) {
                    mainIndexByKey.put(song.getSongKey(), mainIndexByKey.size() + 1);
                }
            }
            int mainCount = mainIndexByKey.size();
            int playedIndex = 0;
            for (ShowSong song : firstOccurrence.values()) {
                playedIndex++;
                byKey.computeIfAbsent(song.getSongKey(), key -> new Accumulator(song.getSongName()))
                        .add(song, new SongContext(show, weight, decayWeight, i, halfBoundary,
                                playedIndex,
                                mainIndexByKey.getOrDefault(song.getSongKey(), 0), mainCount));
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
                            (double) acc.playedCount / sampleSize,
                            acc.unboostedScore / unboostedTotalWeight,
                            acc.recentCount5,
                            trend(acc, sampleSize, halfBoundary),
                            new PositionStats(acc.openerCount, acc.earlyCount, acc.midCount,
                                    acc.lateCount, acc.encoreCount),
                            new TypeBreakdown(festivalShows, acc.festivalPlayed, soloShows, acc.soloPlayed),
                            List.copyOf(acc.appearances))));
        }
        scores.sort(Comparator.comparing(SongScore::probability).reversed()
                .thenComparing(Comparator.comparingInt(SongScore::playedCount).reversed())
                .thenComparing(SongScore::songKey));
        return scores;
    }

    private static Trend trend(Accumulator acc, int sampleSize, int halfBoundary) {
        // 표본 1회면 최근 절반이 비어 비교 자체가 성립하지 않는다
        if (halfBoundary == 0) {
            return Trend.STABLE;
        }
        double recentRate = (double) acc.recentHalfCount / halfBoundary;
        double olderRate = (double) acc.olderHalfCount / (sampleSize - halfBoundary);
        double diff = recentRate - olderRate;
        if (diff >= TREND_THRESHOLD) {
            return Trend.RISING;
        }
        if (diff <= -TREND_THRESHOLD) {
            return Trend.FALLING;
        }
        return Trend.STABLE;
    }

    private static BigDecimal toScale(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 공연 1회분의 곡 집계 컨텍스트. playedIndex는 실연주(tape·중복 제외) 기준 1-base 순번 —
     * positionTotal은 인트로 테이프만큼 밀려 "보통 N번째 곡" 표기가 어긋난다.
     * mainIndex는 본편(앙코르 제외) 내 순번, 앙코르 곡이면 0.
     */
    private record SongContext(Show show, double weight, double decayWeight, int showIndex,
                               int halfBoundary, int playedIndex, int mainIndex, int mainCount) {
    }

    private static final class Accumulator {
        /** 최근 공연부터 순회하므로 처음 만난 표기가 가장 최신 원본 곡명이다. */
        private final String songName;
        private double weightedScore;
        private double unboostedScore;
        private int playedCount;
        private int positionSum;
        private int encoreCount;
        private int recentCount5;
        private int recentHalfCount;
        private int olderHalfCount;
        private int openerCount;
        private int earlyCount;
        private int midCount;
        private int lateCount;
        private int festivalPlayed;
        private int soloPlayed;
        private final List<Appearance> appearances = new ArrayList<>();

        private Accumulator(String songName) {
            this.songName = songName;
        }

        private void add(ShowSong song, SongContext ctx) {
            weightedScore += ctx.weight();
            unboostedScore += ctx.decayWeight();
            playedCount++;
            // avgPosition의 원천 — positionTotal이 아니라 실연주 순번(D10: 인트로 테이프 오프셋 보정)
            positionSum += ctx.playedIndex();
            if (song.isEncore()) {
                encoreCount++;
            } else if (ctx.mainIndex() == 1) {
                openerCount++;
            } else if (ctx.mainIndex() <= Math.ceilDiv(ctx.mainCount(), 3)) {
                earlyCount++;
            } else if (ctx.mainIndex() <= Math.ceilDiv(2 * ctx.mainCount(), 3)) {
                midCount++;
            } else {
                lateCount++;
            }
            if (ctx.showIndex() < 5) {
                recentCount5++;
            }
            if (ctx.showIndex() < ctx.halfBoundary()) {
                recentHalfCount++;
            } else {
                olderHalfCount++;
            }
            if (ctx.show().getShowType() == ShowType.FESTIVAL) {
                festivalPlayed++;
            } else if (ctx.show().getShowType() == ShowType.SOLO) {
                soloPlayed++;
            }
            appearances.add(new Appearance(ctx.show().getSetlistId(),
                    ctx.show().getEventDate().toString(),
                    ctx.weight(), song.getPositionTotal(), song.isEncore()));
        }
    }
}
