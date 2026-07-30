package com.encore.api;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
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
 * songKey는 아티스트 안에서만 유일하므로 artistMbid가 필수다. 검색 질의·프롬프트에 쓰는
 * 원본 곡명은 클라이언트가 보내지 않고 서버가 예측 스냅샷(prediction.song_name)에서 읽는다 —
 * 파라미터로 받으면 임의 텍스트가 프롬프트에 들어가 공용 캐시를 오염시킬 수 있다.
 */
@RestController
@RequestMapping("/api/songs")
public class SongExplanationController {

    private final SongExplanationService explanationService;
    private final ArtistRepository artistRepository;
    private final PredictionRepository predictionRepository;
    private final ObjectMapper objectMapper;

    public SongExplanationController(SongExplanationService explanationService,
                                    ArtistRepository artistRepository,
                                    PredictionRepository predictionRepository,
                                    ObjectMapper objectMapper) {
        this.explanationService = explanationService;
        this.artistRepository = artistRepository;
        this.predictionRepository = predictionRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{songKey}/explanation", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> explanation(@PathVariable String songKey,
                                                     @RequestParam UUID artistMbid) {
        Artist artist = artistRepository.findById(artistMbid)
                .orElseThrow(() -> new ApiNotFoundException("등록되지 않은 아티스트입니다: " + artistMbid));
        // 예측에 있는 곡만 — 공개 엔드포인트라 임의 songKey로 임베딩·LLM 비용이 새면 안 된다.
        // 곡명도 여기서 읽는다(클라이언트 값 불신) — 예측 존재 검증을 겸한다
        Prediction prediction = predictionRepository
                .findFirstByTargetEvent_Artist_MbidAndSongKey(artistMbid, songKey)
                .orElseThrow(() -> new ApiNotFoundException("예측에 없는 곡입니다: " + songKey));

        SongExplanationService.Explanation explanation = explanationService.explain(
                artistMbid, songKey, prediction.getSongName(), artist.getName());

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
