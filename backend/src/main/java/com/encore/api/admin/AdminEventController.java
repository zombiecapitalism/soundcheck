package com.encore.api.admin;

import com.encore.api.ApiNotFoundException;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.CollectionLog;
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
    private final AdminBatchService batchService;

    public AdminEventController(ArtistRepository artistRepository,
                                TargetEventRepository targetEventRepository,
                                AdminBatchService batchService) {
        this.artistRepository = artistRepository;
        this.targetEventRepository = targetEventRepository;
        this.batchService = batchService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedEvent create(@Valid @RequestBody CreateEventRequest request) {
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

        // 예측은 조회·계산뿐이라 등록 직후 동기로 돌려도 부담이 없다.
        // 아직 수집 전이면 FAILED("집계할 공연이 없습니다")로 남는다 — 수집 후 재실행하면 된다.
        String predictionStatus = batchService.predictNow().stream()
                .filter(log -> artist.getMbid().equals(log.getArtistMbid()))
                .filter(log -> log.getStartedAt() != null)
                .reduce((first, second) -> second) // 방금 실행분 = 마지막 로그
                .map(CollectionLog::getStatus)
                .map(Enum::name)
                .orElse("NOT_RUN");
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
