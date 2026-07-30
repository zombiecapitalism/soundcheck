package com.encore.api;

import com.encore.prediction.EvidenceJson;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionCalculator.Appearance;
import com.encore.prediction.PredictionCalculator.Evidence;
import com.encore.prediction.PredictionConfidence;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /api/events/{id}/predictions/{songKey} — 곡 하나의 예측 근거를 타임라인으로 푼다.
 * history는 예측과 같은 표본 선정 규칙의 최근 공연 목록(최근순)이며, 연주/미연주를
 * 모두 담아 "최근 20회 중 19회"의 공백까지 보이게 한다.
 * evidence 블록(E1)은 확률 계산의 분해 — v0.2 이전 스냅샷은 확장 필드가 null이다.
 */
public record PredictionDetailResponse(
        PredictionResponse prediction,
        PredictionConfidence confidence,
        EvidenceBlock evidence,
        List<HistoryEntry> history
) {

    /** "왜 이 확률인가" — 등장률 × 최신성 × 유형 부스트 분해. */
    public record EvidenceBlock(
            double baseFrequency,
            double weightedScore,
            double totalWeight,
            double recencyDecay,
            double matchingShowTypeBoost,
            /** 유형 부스트가 확률에 기여한 정도(확률 − 부스트 없는 확률). 구버전 evidence면 null. */
            Double boostEffect,
            PositionStats positionStats,
            TypeBreakdown typeBreakdown
    ) {

        public record PositionStats(int opener, int early, int mid, int late, int encore) {
        }

        public record TypeBreakdown(int festivalShows, int festivalPlayed, int soloShows, int soloPlayed) {
        }

        static EvidenceBlock from(Evidence evidence, double probability) {
            return new EvidenceBlock(
                    evidence.baseFrequency(),
                    evidence.weightedScore(),
                    evidence.totalWeight(),
                    evidence.recencyDecay(),
                    evidence.matchingShowTypeBoost(),
                    evidence.unboostedProbability() != null
                            ? probability - evidence.unboostedProbability()
                            : null,
                    evidence.positionStats() != null
                            ? new PositionStats(evidence.positionStats().opener(),
                                    evidence.positionStats().early(), evidence.positionStats().mid(),
                                    evidence.positionStats().late(), evidence.positionStats().encore())
                            : null,
                    evidence.typeBreakdown() != null
                            ? new TypeBreakdown(evidence.typeBreakdown().festivalShows(),
                                    evidence.typeBreakdown().festivalPlayed(),
                                    evidence.typeBreakdown().soloShows(),
                                    evidence.typeBreakdown().soloPlayed())
                            : null);
        }
    }

    public record HistoryEntry(
            String setlistId,
            LocalDate eventDate,
            String venueName,
            String cityName,
            ShowType showType,
            int playedSongCount,
            boolean played,
            Integer position,
            Boolean encore,
            /** 이 공연이 확률 계산에 기여한 가중치 — 미연주 공연이거나 구버전 evidence면 null. */
            Double weight
    ) {

        /** show의 곡 목록은 fetch join으로 로드된 상태를 전제한다. */
        static HistoryEntry from(Show show, String songKey, Map<String, Double> weightBySetlistId) {
            ShowSong song = show.playedSongs().stream()
                    .filter(s -> s.getSongKey().equals(songKey))
                    .findFirst()
                    .orElse(null);
            return new HistoryEntry(
                    show.getSetlistId(),
                    show.getEventDate(),
                    show.getVenueName(),
                    show.getCityName(),
                    show.getShowType(),
                    show.playedSongs().size(),
                    song != null,
                    song != null ? (int) song.getPositionTotal() : null,
                    song != null ? song.isEncore() : null,
                    weightBySetlistId.get(show.getSetlistId()));
        }
    }

    public static PredictionDetailResponse from(Prediction prediction, List<Show> sampleShows) {
        Evidence evidence = EvidenceJson.parse(prediction.getEvidence());
        // appearances 키만 빠진 손상 JSON도 파싱은 성공한다 — 근거 표시만 포기하고 죽지 않는다
        Map<String, Double> weightBySetlistId = evidence == null || evidence.appearances() == null
                ? Map.of()
                : evidence.appearances().stream()
                        .collect(Collectors.toMap(Appearance::setlistId, Appearance::weight,
                                // 같은 공연에 중복 기록이 있어도(방어) 첫 값을 유지한다
                                (first, second) -> first));
        return new PredictionDetailResponse(
                PredictionResponse.from(prediction),
                PredictionConfidence.of(prediction.getSampleSize(),
                        prediction.getProbability().doubleValue()),
                evidence != null
                        ? EvidenceBlock.from(evidence, prediction.getProbability().doubleValue())
                        : null,
                sampleShows.stream()
                        .map(show -> HistoryEntry.from(show, prediction.getSongKey(), weightBySetlistId))
                        .toList());
    }
}
