package com.encore.api;

import com.encore.playlist.PlaylistLinks;
import com.encore.playlist.PlaylistService;
import com.encore.playlist.YoutubeProperties;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 재생목록(E12) — 선택한 예측 곡을 YouTube 임시 재생목록 링크로 만든다.
 * POST인 이유: 캐시 미스 곡은 YouTube 검색(쿼터 소모)이 실행되는 부수효과가 있다.
 */
@RestController
@RequestMapping("/api/events")
public class PlaylistController {

    public record PlaylistRequest(List<String> songKeys) {
    }

    private final PlaylistService playlistService;
    private final TargetEventRepository targetEventRepository;
    private final YoutubeProperties youtubeProperties;

    public PlaylistController(PlaylistService playlistService,
                              TargetEventRepository targetEventRepository,
                              YoutubeProperties youtubeProperties) {
        this.playlistService = playlistService;
        this.targetEventRepository = targetEventRepository;
        this.youtubeProperties = youtubeProperties;
    }

    @PostMapping("/{id}/playlist")
    public PlaylistService.Playlist playlist(@PathVariable Long id,
                                             @RequestBody PlaylistRequest request) {
        // 검색 질의에 아티스트 이름이 필요하다 — 트랜잭션 밖 지연 로딩 불가라 fetch join
        TargetEvent event = targetEventRepository.findByIdWithArtist(id)
                .orElseThrow(() -> new ApiNotFoundException("존재하지 않는 이벤트입니다: " + id));
        if (!youtubeProperties.enabled()) {
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                            "재생목록 기능이 아직 설정되지 않았어요 (YouTube API 키 없음)"), null);
        }
        if (request == null || request.songKeys() == null || request.songKeys().isEmpty()) {
            throw new IllegalArgumentException("곡을 하나 이상 선택해 주세요");
        }
        // 서비스가 중복을 제거하므로 상한도 중복 제거 후 기준으로 검사한다
        if (request.songKeys().stream().distinct().count() > PlaylistLinks.MAX_VIDEOS) {
            throw new IllegalArgumentException(
                    "한 번에 " + PlaylistLinks.MAX_VIDEOS + "곡까지 담을 수 있어요");
        }
        return playlistService.build(event, request.songKeys());
    }
}
