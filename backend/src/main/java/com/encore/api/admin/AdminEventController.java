package com.encore.api.admin;

import com.encore.api.ApiNotFoundException;
import com.encore.artist.Artist;
import com.encore.common.KoreaTime;
import com.encore.artist.ArtistRepository;
import com.encore.pipeline.CollectionPipeline;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.ShowType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** 관리자 — 예측 대상 이벤트 등록. 등록 직후 예측을 즉시 계산한다. */
@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final ArtistRepository artistRepository;
    private final TargetEventRepository targetEventRepository;
    private final PredictionRepository predictionRepository;
    private final CollectionPipeline pipeline;

    public AdminEventController(ArtistRepository artistRepository,
                                TargetEventRepository targetEventRepository,
                                PredictionRepository predictionRepository,
                                CollectionPipeline pipeline) {
        this.artistRepository = artistRepository;
        this.targetEventRepository = targetEventRepository;
        this.predictionRepository = predictionRepository;
        this.pipeline = pipeline;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedEvent create(@Valid @RequestBody CreateEventRequest request) {
        // 지난 공연은 예측 스냅샷을 만들 수 없다(사후 예측은 공연 이후 데이터가 새는 것) —
        // 검증됐는데 성적이 없는 유령 이벤트가 되므로 등록 자체를 거부한다.
        if (request.eventDate().isBefore(KoreaTime.today())) {
            throw new IllegalArgumentException(
                    "지난 날짜의 이벤트는 등록할 수 없습니다: " + request.eventDate());
        }
        Artist artist = artistRepository.findById(request.artistMbid())
                .orElseThrow(() -> new ApiNotFoundException("등록되지 않은 아티스트입니다: " + request.artistMbid()));

        // saveAndFlush: 유니크 충돌(같은 아티스트·날짜)이 예측 실행 전에 즉시 드러나야 409로 변환된다
        TargetEvent event = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist)
                .eventName(request.eventName())
                .eventDate(request.eventDate())
                .venueName(request.venueName())
                .expectedShowType(request.expectedShowType())
                .expectedSongCount(request.expectedSongCount())
                .build());

        // 이 이벤트만 예측한다 — 전체 재계산은 이벤트 수 × LLM 변화 요약 비용이 등록마다 든다.
        // 아직 수집 전이면 FAILED("집계할 공연이 없습니다")로 남는다 — 수집 후 재실행하면 된다.
        // 배치와 겹치면 건너뛴다 — 진행 중인 배치가 이 이벤트도 계산한다.
        boolean predicted = pipeline.tryPredictSingle(event);
        // 로그에서 "방금 실행분"을 추측하는 대신 이 이벤트의 예측 존재 여부로 판정한다 —
        // 같은 아티스트의 이벤트가 여럿이어도 정확하다.
        String predictionStatus = !predicted
                ? "PENDING"
                : predictionRepository.existsByTargetEvent_Id(event.getId()) ? "SUCCESS" : "FAILED";
        return new CreatedEvent(event.getId(), predictionStatus);
    }

    public record CreateEventRequest(
            @NotNull UUID artistMbid,
            @NotBlank String eventName,
            @NotNull LocalDate eventDate,
            String venueName,
            @NotNull ShowType expectedShowType,
            Short expectedSongCount) {
    }

    public record CreatedEvent(Long id, String predictionStatus) {
    }
}
