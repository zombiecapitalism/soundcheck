package com.encore.api;

import com.encore.prediction.AccuracyCalculator;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.PredictionSampling;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
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
    private final ShowRepository showRepository;

    public EventController(TargetEventRepository targetEventRepository,
                           PredictionRepository predictionRepository,
                           ShowRepository showRepository) {
        this.targetEventRepository = targetEventRepository;
        this.predictionRepository = predictionRepository;
        this.showRepository = showRepository;
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
     * 적중률 아카이브 — 검증된 지난 공연들의 성적 목록(최근 공연부터).
     * 예측 스냅샷이 없는 이벤트(수동 연결 등 예외 상태)는 채점할 수 없어 제외한다.
     */
    @GetMapping("/accuracy")
    public List<AccuracySummaryResponse> accuracyArchive() {
        return targetEventRepository.findAllVerifiedWithActualSongs().stream()
                .map(event -> {
                    List<Prediction> predictions =
                            predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
                    return predictions.isEmpty()
                            ? null
                            : AccuracySummaryResponse.from(event,
                                    AccuracyCalculator.evaluate(predictions, event.getActualSetlist()));
                })
                .filter(summary -> summary != null)
                .toList();
    }

    /**
     * 곡 하나의 예측 상세 — 근거 수치 + 최근 공연 타임라인(연주/미연주 포함).
     * 표본 선정은 예측 계산과 같은 규칙(PredictionSampling)이라 "최근 N회 중 k회"와
     * 타임라인이 어긋나지 않는다. songKey는 URL 인코딩된 정규화 키.
     */
    @GetMapping("/{id}/predictions/{songKey}")
    public PredictionDetailResponse predictionDetail(@PathVariable Long id, @PathVariable String songKey) {
        TargetEvent event = targetEventRepository.findById(id)
                .orElseThrow(() -> new ApiNotFoundException("존재하지 않는 이벤트입니다: " + id));
        Prediction prediction = predictionRepository.findByTargetEvent_IdAndSongKey(id, songKey)
                .orElseThrow(() -> new ApiNotFoundException("예측에 없는 곡입니다: " + songKey));
        // 아티스트 접근은 식별자뿐이라 지연 초기화가 없고, 곡 목록은 fetch join으로 로드된다.
        // 공연일 이후의 공연은 잘라낸다 — 지난(검증된) 이벤트에서 예측은 스냅샷으로 고정인데
        // 타임라인만 새 공연을 포함하면 헤더의 근거 수치와 어긋난다. 미래 이벤트에는 영향 없다.
        List<Show> shows = showRepository.findAllByArtistMbidWithSongs(event.getArtist().getMbid()).stream()
                .filter(show -> !show.getEventDate().isAfter(event.getEventDate()))
                .toList();
        return PredictionDetailResponse.from(prediction,
                PredictionSampling.sample(shows, prediction.getSampleSize()));
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
