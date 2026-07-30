package com.encore.api.admin;

import com.encore.api.ApiNotFoundException;
import com.encore.rag.RagAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 관리자 — RAG 저장소 상태·문서 관리·설명 캐시 관리(E10). 로직은 RagAdminService. */
@RestController
@RequestMapping("/api/admin/rag")
public class AdminRagController {

    private final RagAdminService ragAdminService;

    public AdminRagController(RagAdminService ragAdminService) {
        this.ragAdminService = ragAdminService;
    }

    @GetMapping("/status")
    public List<RagAdminService.ArtistRagStatus> status() {
        return ragAdminService.status();
    }

    @GetMapping("/documents")
    public List<RagAdminService.RagDocumentSummary> documents(@RequestParam UUID artistMbid) {
        return ragAdminService.documents(artistMbid);
    }

    @DeleteMapping("/documents/{id}")
    public void deleteDocument(@PathVariable Long id) {
        if (!ragAdminService.deleteDocument(id)) {
            throw new ApiNotFoundException("존재하지 않는 문서입니다: " + id);
        }
    }

    /** 설명 캐시 아티스트 단위 무효화 — 다음 조회 때 재생성된다. */
    @DeleteMapping("/cache/{artistMbid}")
    public void evictCache(@PathVariable UUID artistMbid) {
        ragAdminService.evictExplanations(artistMbid);
    }
}
