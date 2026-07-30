package com.encore.api;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.rag.SongExplanationService;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 곡 설명 SSE — 이벤트 순서(sources → delta → done)와 프레이밍을 검증한다. LLM은 목이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SongExplanationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private PredictionRepository predictionRepository;

    @MockitoBean
    private SongExplanationService explanationService;

    /** 곡 설명은 예측에 등장한 곡만 허용된다 — 테스트마다 예측을 깔아준다. */
    private void persistPrediction(Artist artist, String songKey, String songName) {
        TargetEvent event = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("검증용 공연").eventDate(LocalDate.of(2099, 10, 2))
                .expectedShowType(ShowType.FESTIVAL).build());
        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(event).songKey(songKey).songName(songName)
                .probability(new BigDecimal("0.9000")).rank((short) 1)
                .playedCount((short) 18).sampleSize((short) 20)
                .build());
    }

    @Test
    void streamsSourcesThenDeltasThenDone() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Avenged Sevenfold").target(true).build());
        persistPrediction(artist, "afterlife", "Afterlife");
        when(explanationService.explain(eq(artist.getMbid()), eq("afterlife"), eq("Afterlife"),
                eq("Avenged Sevenfold")))
                .thenReturn(new SongExplanationService.Explanation(
                        List.of(new SongExplanationService.Source(
                                "Wikipedia", "https://en.wikipedia.org/wiki/Afterlife", "Afterlife (song)")),
                        // 공백으로 시작하는 토큰 — JSON 인코딩이 없으면 SSE 규격이 공백을 삼킨다
                        Flux.just("이 곡은", " 앨범의", " 세 번째 싱글이다.")));

        MvcResult result = mockMvc.perform(get("/api/songs/{songKey}/explanation", "afterlife")
                        .param("artistMbid", artist.getMbid().toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SSE 응답에는 charset 표기가 없어 기본 Latin-1로 읽히면 한글이 깨진다 — UTF-8 명시
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getContentType()).contains("text/event-stream");
        // 순서: sources → delta들 → done
        assertThat(body).containsSubsequence(
                "event:sources", "Wikipedia", "https://en.wikipedia.org/wiki/Afterlife",
                "event:delta", "\"이 곡은\"",
                "event:delta", "\" 앨범의\"",
                "event:delta", "\" 세 번째 싱글이다.\"",
                "event:done");
    }

    @Test
    void unknownArtistIsNotFound() throws Exception {
        mockMvc.perform(get("/api/songs/{songKey}/explanation", "afterlife")
                        .param("artistMbid", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    /** 임의 songKey로 임베딩·LLM 비용이 새면 안 된다 — 예측에 없는 곡은 404다. */
    @Test
    void songNotInPredictionsIsNotFound() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        persistPrediction(artist, "holy wars", "Holy Wars");

        mockMvc.perform(get("/api/songs/{songKey}/explanation", "made up song")
                        .param("artistMbid", artist.getMbid().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void generationFailureBecomesErrorEventNotBrokenStream() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        persistPrediction(artist, "holy wars", "Holy Wars");
        when(explanationService.explain(any(), any(), any(), any()))
                .thenReturn(new SongExplanationService.Explanation(
                        List.of(),
                        Flux.error(new IllegalStateException("LLM 호출 실패"))));

        MvcResult result = mockMvc.perform(get("/api/songs/{songKey}/explanation", "holy wars")
                        .param("artistMbid", artist.getMbid().toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:error");
        assertThat(body).contains("다시 시도");
        assertThat(body).doesNotContain("LLM 호출 실패"); // 내부 메시지를 노출하지 않는다
    }
}
