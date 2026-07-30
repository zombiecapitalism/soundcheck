package com.encore.api;

import com.encore.prediction.AccuracyCalculator;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final TargetEventRepository targetEventRepository;
    private final PredictionRepository predictionRepository;

    public EventController(TargetEventRepository targetEventRepository,
                           PredictionRepository predictionRepository) {
        this.targetEventRepository = targetEventRepository;
        this.predictionRepository = predictionRepository;
    }

    /** 예측 대상 이벤트 목록 — 공연일 오름차순. */
    @GetMapping
    public List<EventResponse> events() {
        return targetEventRepository.findAllWithArtist().stream()
                .map(EventResponse::from)
                .toList();
    }

    /**
     * 곡별 예측 결과, 확률 내림차순(= rank 오름차순).
     * 배치가 사전 계산한 값을 읽기만 한다 — 조회 시 계산하지 않는다.
     * 이벤트는 있는데 예측이 아직 없으면 404가 아니라 빈 배열이다(배치 전 상태).
     */
    @GetMapping("/{id}/predictions")
    public List<PredictionResponse> predictions(@PathVariable Long id) {
        if (!targetEventRepository.existsById(id)) {
            throw new ApiNotFoundException("존재하지 않는 이벤트입니다: " + id);
        }
        return predictionRepository.findByTargetEvent_IdOrderByRankAsc(id).stream()
                .map(PredictionResponse::from)
                .toList();
    }

    /**
     * 공연 후 적중률 — 공연 전 마지막 예측(스냅샷)과 실제 셋리스트의 비교.
     * 실제 셋리스트·곡 목록은 fetch join으로 로드하므로 웹 계층에 트랜잭션이 필요 없다.
     */
    @GetMapping("/{id}/accuracy")
    public AccuracyResponse accuracy(@PathVariable Long id) {
        if (!targetEventRepository.existsById(id)) {
            throw new ApiNotFoundException("존재하지 않는 이벤트입니다: " + id);
        }
        TargetEvent event = targetEventRepository.findVerifiedWithActualSongs(id)
                .orElseThrow(() -> new ApiNotFoundException("아직 실제 셋리스트가 연결되지 않았습니다: " + id));
        return AccuracyResponse.from(AccuracyCalculator.evaluate(
                predictionRepository.findByTargetEvent_IdOrderByRankAsc(id),
                event.getActualSetlist()));
    }
}
