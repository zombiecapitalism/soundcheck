package com.encore.prediction;

import com.encore.prediction.PredictionCalculator.Evidence;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 예측 파생 조회(E6 예상 셋리스트, E11 유사 공연) — 곡 수 폴백·표본 조립 같은
 * 도메인 규칙을 컨트롤러에서 내려 "컨트롤러는 읽고 변환만"이라는 계층 원칙을 지킨다.
 */
@Service
public class EventInsightService {

    /** 유사 공연의 곡 구성 겹침 기준 — 예측 상위 K곡. */
    static final int SIMILAR_TOP_KEYS = 20;
    static final int SIMILAR_LIMIT = 3;

    public record ExpectedSetlist(SetlistComposer.Composed composed, int expectedSongCount) {
    }

    private final PredictionRepository predictionRepository;
    private final ShowRepository showRepository;

    public EventInsightService(PredictionRepository predictionRepository,
                               ShowRepository showRepository) {
        this.predictionRepository = predictionRepository;
        this.showRepository = showRepository;
    }

    /**
     * 예상 셋리스트(E6) — 저장하지 않고 조회 시 구성한다.
     * 곡 수는 유형별 평균(없으면 전체 평균, 그마저 없으면 확률 ≥ 0.5 곡 수)을 반올림.
     * 예측이 아직 없으면 빈 블록(목록 API와 같은 "준비 중" 계약).
     */
    public ExpectedSetlist expectedSetlist(TargetEvent event) {
        List<Prediction> predictions =
                predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
        if (predictions.isEmpty()) {
            return new ExpectedSetlist(SetlistComposer.compose(List.of(), 0), 0);
        }

        UUID mbid = event.getArtist().getMbid();
        Double avg = showRepository.averageSongCountByType(mbid, event.getExpectedShowType());
        if (avg == null) {
            avg = showRepository.averageSongCount(mbid);
        }
        long likely = predictions.stream()
                .filter(p -> p.getProbability().doubleValue() >= 0.5)
                .count();
        int expectedSongCount = avg != null
                ? (int) Math.round(avg)
                : (int) Math.max(likely, 1);

        List<SetlistComposer.Entry> entries = predictions.stream()
                .map(EventInsightService::toComposerEntry)
                .toList();
        return new ExpectedSetlist(
                SetlistComposer.compose(entries, expectedSongCount), expectedSongCount);
    }

    /** 유사 공연(E11) — 과거 공연 상위 3건. 점수 규칙은 SimilarShowScorer(순수 함수). */
    public List<SimilarShowScorer.ScoredShow> similarShows(TargetEvent event) {
        Set<String> topKeys = predictionRepository
                .findByTargetEvent_IdOrderByRankAsc(event.getId()).stream()
                .limit(SIMILAR_TOP_KEYS)
                .map(Prediction::getSongKey)
                .collect(Collectors.toSet());
        List<Show> pastShows = showRepository
                .findAllByArtistMbidWithSongs(event.getArtist().getMbid()).stream()
                .filter(show -> show.getEventDate().isBefore(event.getEventDate()))
                .toList();
        return SimilarShowScorer.topSimilar(
                pastShows, event.getExpectedShowType(), event.getEventDate(), topKeys, SIMILAR_LIMIT);
    }

    private static SetlistComposer.Entry toComposerEntry(Prediction prediction) {
        Evidence evidence = EvidenceJson.parse(prediction.getEvidence());
        Double openerRate = evidence != null && evidence.positionStats() != null
                && prediction.getPlayedCount() > 0
                ? (double) evidence.positionStats().opener() / prediction.getPlayedCount()
                : null;
        return new SetlistComposer.Entry(
                prediction.getRank(),
                prediction.getSongKey(),
                prediction.getSongName(),
                prediction.getProbability(),
                prediction.getAvgPosition(),
                prediction.getEncoreRatio(),
                openerRate);
    }
}
