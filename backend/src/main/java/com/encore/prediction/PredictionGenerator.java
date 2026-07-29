package com.encore.prediction;

import com.encore.prediction.PredictionCalculator.Params;
import com.encore.prediction.PredictionCalculator.SongScore;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 예측 대상 이벤트 하나를 계산해 prediction 테이블에 반영한다.
 * 재계산은 갱신이 아니라 전체 교체 — 삭제와 삽입이 한 트랜잭션에서 끝난다.
 */
@Component
public class PredictionGenerator {

    public record Summary(int sampleSize, int savedPredictions) {
    }

    private final TargetEventRepository targetEventRepository;
    private final ShowRepository showRepository;
    private final PredictionRepository predictionRepository;
    private final PredictionProperties properties;
    private final ObjectMapper objectMapper;

    public PredictionGenerator(TargetEventRepository targetEventRepository, ShowRepository showRepository,
                               PredictionRepository predictionRepository, PredictionProperties properties,
                               ObjectMapper objectMapper) {
        this.targetEventRepository = targetEventRepository;
        this.showRepository = showRepository;
        this.predictionRepository = predictionRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Summary predict(Long targetEventId) {
        // 트랜잭션 밖에서 넘어온 detached 엔티티에 기대지 않도록 id로 다시 로드한다
        TargetEvent event = targetEventRepository.findById(targetEventId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 예측 대상: " + targetEventId));

        List<Show> sample = showRepository.findAllByArtistMbidWithSongs(event.getArtist().getMbid()).stream()
                .limit(properties.sampleSize())
                .toList();
        if (sample.isEmpty()) {
            throw new IllegalStateException("집계할 공연이 없습니다: " + event.getEventName());
        }

        List<SongScore> scores = PredictionCalculator.calculate(sample, event.getExpectedShowType(),
                new Params(properties.recencyDecay(), properties.matchingShowTypeBoost()));

        predictionRepository.deleteByTargetEventId(event.getId());
        List<Prediction> rows = new ArrayList<>(scores.size());
        short rank = 0;
        for (SongScore score : scores) {
            rank++;
            rows.add(Prediction.builder()
                    .targetEvent(event)
                    .songKey(score.songKey())
                    .songName(score.songName())
                    .probability(score.probability())
                    .rank(rank)
                    .playedCount((short) score.playedCount())
                    .sampleSize((short) score.sampleSize())
                    .avgPosition(score.avgPosition())
                    .encoreRatio(score.encoreRatio())
                    .evidence(objectMapper.writeValueAsString(score.evidence()))
                    .build());
        }
        predictionRepository.saveAll(rows);
        return new Summary(sample.size(), rows.size());
    }
}
