package com.encore.api.admin;

import com.encore.setlist.FestivalMapping;
import com.encore.setlist.FestivalMappingRepository;
import com.encore.setlist.ShowReclassifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 — 페스티벌 수동 매핑 관리와 기존 공연 재분류.
 * 대형 페스티벌은 스테이지명(예: 후지록 "GREEN STAGE")으로 등록되는 경우가 많아
 * 기본 키워드(festival)의 재현율이 낮다. 매핑 추가 후 반드시 재분류를 실행해야
 * 이미 수집된 공연에 반영된다.
 */
@RestController
@RequestMapping("/api/admin/festival-mappings")
public class AdminFestivalMappingController {

    private final FestivalMappingRepository festivalMappingRepository;
    private final ShowReclassifier showReclassifier;

    public AdminFestivalMappingController(FestivalMappingRepository festivalMappingRepository,
                                          ShowReclassifier showReclassifier) {
        this.festivalMappingRepository = festivalMappingRepository;
        this.showReclassifier = showReclassifier;
    }

    @GetMapping
    public List<MappingEntry> list() {
        return festivalMappingRepository.findAll().stream()
                .map(m -> new MappingEntry(m.getId(), m.getKeyword()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Object> add(@RequestBody AddRequest request) {
        String keyword = request.keyword() == null ? "" : request.keyword().strip();
        if (keyword.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "키워드를 입력하세요.");
            problem.setTitle("잘못된 요청");
            return ResponseEntity.of(problem).build();
        }
        if (festivalMappingRepository.existsByKeyword(keyword)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "이미 등록된 키워드입니다: " + keyword);
            problem.setTitle("중복 키워드");
            return ResponseEntity.of(problem).build();
        }
        FestivalMapping saved = festivalMappingRepository.save(new FestivalMapping(keyword));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MappingEntry(saved.getId(), saved.getKeyword()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!festivalMappingRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        festivalMappingRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 전체 공연 재판정(동기). 키워드 추가·삭제 후 호출해야 기존 수집분에 반영된다. */
    @PostMapping("/reclassify")
    public ReclassifyResult reclassify() {
        return new ReclassifyResult(showReclassifier.reclassifyAll());
    }

    public record MappingEntry(Long id, String keyword) {
    }

    public record AddRequest(String keyword) {
    }

    public record ReclassifyResult(int changed) {
    }
}
