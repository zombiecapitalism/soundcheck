package com.encore.api.admin;

import com.encore.batch.CollectionLog;
import com.encore.pipeline.CollectionPipeline;
import com.encore.batch.CollectionLogRepository;
import com.encore.rag.RagIngestRunner;
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

    private final CollectionPipeline pipeline;
    private final CollectionLogRepository collectionLogRepository;
    private final RagIngestRunner ragIngestRunner;

    public AdminBatchController(CollectionPipeline pipeline,
                                CollectionLogRepository collectionLogRepository,
                                RagIngestRunner ragIngestRunner) {
        this.pipeline = pipeline;
        this.collectionLogRepository = collectionLogRepository;
        this.ragIngestRunner = ragIngestRunner;
    }

    /** 수집 시작(비동기) — 완료되면 매칭·예측까지 이어진다. 이미 실행 중이면 409. */
    @PostMapping("/batch/collect")
    public ResponseEntity<Object> collect() {
        if (!pipeline.tryStartCollection()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "수집이 이미 실행 중입니다. 완료 후 다시 시도하세요.");
            problem.setTitle("이미 실행 중");
            return ResponseEntity.of(problem).build();
        }
        return ResponseEntity.accepted().body(new CollectStarted(true));
    }

    /** 적중률 매칭 + 예측 재계산(동기) — 다가오는 이벤트 전체. */
    @PostMapping("/batch/predict")
    public List<LogEntry> predict() {
        return pipeline.matchAndPredict().stream().map(LogEntry::from).toList();
    }

    /** RAG 문서 수집 시작(비동기) — 대상 아티스트 전체. 이력은 EMBED 타입으로 남는다. */
    @PostMapping("/batch/rag-ingest")
    public ResponseEntity<Object> ragIngest() {
        if (!ragIngestRunner.tryStart()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "RAG 수집이 이미 실행 중입니다. 완료 후 다시 시도하세요.");
            problem.setTitle("이미 실행 중");
            return ResponseEntity.of(problem).build();
        }
        return ResponseEntity.accepted().body(new CollectStarted(true));
    }

    /** 최근 배치 이력 + 수집 진행 여부. */
    @GetMapping("/logs")
    public LogsResponse logs() {
        return new LogsResponse(
                pipeline.isCollecting(),
                ragIngestRunner.isRunning(),
                collectionLogRepository.findTop30ByOrderByIdDesc().stream().map(LogEntry::from).toList());
    }

    public record CollectStarted(boolean started) {
    }

    public record LogsResponse(boolean collecting, boolean ragIngesting, List<LogEntry> logs) {
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
