package com.encore.api;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.rag.SongExplanationService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 곡 배경 설명 — SSE 스트리밍.
 * 이벤트 순서: sources(출처 목록 JSON) → delta(본문 토큰, JSON 문자열) → done.
 * 실패는 연결을 끊는 대신 error 이벤트로 알린다 — EventSource는 연결이 끊기면
 * 자동 재연결하므로, 프론트가 done/error에서 스스로 닫아야 한다.
 * <p>
 * delta를 JSON 문자열로 감싸는 이유: SSE 규격은 "data:" 뒤의 공백 하나를 벗겨내는데,
 * LLM 토큰은 공백으로 시작하는 경우가 많아 그대로 보내면 단어 사이 공백이 사라진다.
 * <p>
 * songKey는 아티스트 안에서만 유일하므로 artistMbid가 필수다. songName(원본 곡명)은
 * 검색 질의와 프롬프트에 쓴다 — 정규화 키는 손실 변환이라 질의로 부적합하다.
 */
@RestController
@RequestMapping("/api/songs")
public class SongExplanationController {

    private final SongExplanationService explanationService;
    private final ArtistRepository artistRepository;
    private final ObjectMapper objectMapper;

    public SongExplanationController(SongExplanationService explanationService,
                                    ArtistRepository artistRepository, ObjectMapper objectMapper) {
        this.explanationService = explanationService;
        this.artistRepository = artistRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{songKey}/explanation", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> explanation(@PathVariable String songKey,
                                                     @RequestParam UUID artistMbid,
                                                     @RequestParam String songName) {
        Artist artist = artistRepository.findById(artistMbid)
                .orElseThrow(() -> new ApiNotFoundException("등록되지 않은 아티스트입니다: " + artistMbid));

        SongExplanationService.Explanation explanation =
                explanationService.explain(artistMbid, songKey, songName, artist.getName());

        Flux<ServerSentEvent<String>> sources = Flux.just(event("sources",
                objectMapper.writeValueAsString(explanation.sources())));
        Flux<ServerSentEvent<String>> deltas = explanation.tokens()
                .map(token -> event("delta", objectMapper.writeValueAsString(token)));
        Flux<ServerSentEvent<String>> done = Flux.just(event("done", "{}"));

        return Flux.concat(sources, deltas, done)
                .onErrorResume(e -> Flux.just(
                        event("error", objectMapper.writeValueAsString(
                                "설명 생성에 실패했어요. 잠시 후 다시 시도해 주세요."))));
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
