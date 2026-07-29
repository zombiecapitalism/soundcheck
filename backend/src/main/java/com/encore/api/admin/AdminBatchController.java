package com.encore.api.admin;

import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 관리자 — 배치 수동 트리거와 실행 이력 대시보드. */
@RestController
@RequestMapping("/api/admin")
public class AdminBatchController {

    private final AdminBatchService batchService;
    private final CollectionLogRepository collectionLogRepository;

    public AdminBatchController(AdminBatchService batchService,
                                CollectionLogRepository collectionLogRepository) {
        this.batchService = batchService;
        this.collectionLogRepository = collectionLogRepository;
    }

    /** 수집 시작(비동기). 페스티벌 직후 즉시 재수집 용도(docs 4장). 이미 실행 중이면 409. */
    @PostMapping("/batch/collect")
    public ResponseEntity<Object> collect() {
        if (!batchService.tryStartCollection()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "수집이 이미 실행 중입니다. 완료 후 다시 시도하세요.");
            problem.setTitle("이미 실행 중");
            return ResponseEntity.of(problem).build();
        }
        return ResponseEntity.accepted().body(new CollectStarted(true));
    }

    /** 예측 재계산(동기) — 다가오는 이벤트 전체. */
    @PostMapping("/batch/predict")
    public List<LogEntry> predict() {
        return batchService.predictNow().stream().map(LogEntry::from).toList();
    }

    /** 최근 배치 이력 + 수집 진행 여부. */
    @GetMapping("/logs")
    public LogsResponse logs() {
        return new LogsResponse(
                batchService.isCollecting(),
                collectionLogRepository.findTop30ByOrderByIdDesc().stream().map(LogEntry::from).toList());
    }

    public record CollectStarted(boolean started) {
    }

    public record LogsResponse(boolean collecting, List<LogEntry> logs) {
    }

    public record LogEntry(Long id, String jobType, String status, UUID artistMbid,
                           int fetched, int updated, int skipped,
                           String errorMessage, Instant startedAt, Instant finishedAt) {

        static LogEntry from(CollectionLog log) {
            return new LogEntry(
                    log.getId(),
                    log.getJobType().name(),
                    log.getStatus().name(),
                    log.getArtistMbid(),
                    log.getCounts() != null ? log.getCounts().getFetched() : 0,
                    log.getCounts() != null ? log.getCounts().getUpdated() : 0,
                    log.getCounts() != null ? log.getCounts().getSkipped() : 0,
                    log.getErrorMessage(),
                    log.getStartedAt(),
                    log.getFinishedAt());
        }
    }
}
