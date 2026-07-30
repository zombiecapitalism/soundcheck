package com.encore.api;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 조회 API 통합 테스트 — 실제 DB(Testcontainers)에 시드하고 직렬화 형식까지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private PredictionRepository predictionRepository;
    @Autowired
    private EntityManager entityManager;

    private Artist artist;

    @BeforeEach
    void setUp() {
        artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").sortName("Megadeth")
                .setlistFmUrl("https://www.setlist.fm/setlists/megadeth.html").target(true)
                .build());
    }

    private TargetEvent persistEvent(String name, LocalDate date) {
        return targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName(name).eventDate(date).venueName("삼락생태공원")
                .expectedShowType(ShowType.FESTIVAL)
                .build());
    }

    private void persistShow(String id, LocalDate date, ShowType type, int songCount) {
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .showType(type).rawJson("{}")
                .build();
        show.replaceSongs(java.util.stream.IntStream.rangeClosed(1, songCount)
                .mapToObj(i -> ShowSong.builder()
                        .setIndex((short) 0).positionInSet((short) i).positionTotal((short) i)
                        .songName("Song " + i).songKey("song " + i)
                        .build())
                .map(ShowSong.class::cast)
                .collect(java.util.stream.Collectors.toList()));
        entityManager.persist(show);
    }

    @Test
    void listsEventsOrderedByDateWithArtist() throws Exception {
        persistEvent("나중 이벤트", LocalDate.of(2026, 10, 3));
        persistEvent("먼저 이벤트", LocalDate.of(2026, 10, 2));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventName").value("먼저 이벤트"))
                .andExpect(jsonPath("$[0].eventDate").value("2026-10-02"))
                .andExpect(jsonPath("$[0].venueName").value("삼락생태공원"))
                .andExpect(jsonPath("$[0].expectedShowType").value("FESTIVAL"))
                .andExpect(jsonPath("$[0].artist.mbid").value(artist.getMbid().toString()))
                .andExpect(jsonPath("$[0].artist.name").value("Megadeth"))
                .andExpect(jsonPath("$[1].eventName").value("나중 이벤트"));
    }

    @Test
    void returnsPredictionsOrderedByRankWithEvidenceNumbers() throws Exception {
        TargetEvent event = persistEvent("2026 부산국제록페스티벌", LocalDate.of(2026, 10, 2));
        predictionRepository.saveAll(List.of(
                Prediction.builder()
                        .targetEvent(event).songKey("holy wars").songName("Holy Wars... The Punishment Due")
                        .probability(new BigDecimal("0.9500")).rank((short) 1)
                        .playedCount((short) 19).sampleSize((short) 20)
                        .avgPosition(new BigDecimal("2.5")).encoreRatio(new BigDecimal("0.0500"))
                        .build(),
                Prediction.builder()
                        .targetEvent(event).songKey("trust").songName("Trust")
                        .probability(new BigDecimal("0.6000")).rank((short) 2)
                        .playedCount((short) 12).sampleSize((short) 20)
                        .build()));

        mockMvc.perform(get("/api/events/{id}/predictions", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].songName").value("Holy Wars... The Punishment Due"))
                .andExpect(jsonPath("$[0].probability").value(closeTo(0.95, 1e-9)))
                .andExpect(jsonPath("$[0].playedCount").value(19))
                .andExpect(jsonPath("$[0].sampleSize").value(20))
                .andExpect(jsonPath("$[0].avgPosition").value(closeTo(2.5, 1e-9)))
                .andExpect(jsonPath("$[0].encoreRatio").value(closeTo(0.05, 1e-9)))
                .andExpect(jsonPath("$[1].rank").value(2))
                // 계산 전 값은 키 생략이 아니라 명시적 null로 내려간다(실측) — 프론트 타입도 number|null
                .andExpect(jsonPath("$[1].avgPosition").value(nullValue()));
    }

    /** 이벤트는 있는데 배치가 아직 안 돈 상태 — 404가 아니라 빈 배열이다. */
    @Test
    void returnsEmptyArrayWhenPredictionsNotComputedYet() throws Exception {
        TargetEvent event = persistEvent("아직 계산 전", LocalDate.of(2026, 10, 2));

        mockMvc.perform(get("/api/events/{id}/predictions", event.getId()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    /** 공연 후: 실제 셋리스트가 연결된 이벤트는 verified=true + 적중률 조회가 가능해야 한다. */
    @Test
    void verifiedEventExposesAccuracyReport() throws Exception {
        TargetEvent event = persistEvent("검증된 이벤트", LocalDate.of(2026, 7, 1));
        predictionRepository.saveAll(List.of(
                Prediction.builder()
                        .targetEvent(event).songKey("holy wars").songName("Holy Wars")
                        .probability(new BigDecimal("0.9500")).rank((short) 1)
                        .playedCount((short) 19).sampleSize((short) 20)
                        .build(),
                Prediction.builder()
                        .targetEvent(event).songKey("trust").songName("Trust")
                        .probability(new BigDecimal("0.6000")).rank((short) 2)
                        .playedCount((short) 12).sampleSize((short) 20)
                        .build()));
        persistShow("acc-s1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, 0);
        Show actual = entityManager.find(Show.class, "acc-s1");
        actual.replaceSongs(List.of(
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                        .songName("Holy Wars").songKey("holy wars").build(),
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 2).positionTotal((short) 2)
                        .songName("Symphony of Destruction").songKey("symphony of destruction").build()));
        event.recordActualSetlist(actual);
        entityManager.flush();

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verified").value(true));

        mockMvc.perform(get("/api/events/{id}/accuracy", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualSongCount").value(2))
                .andExpect(jsonPath("$.topK").value(2))
                .andExpect(jsonPath("$.topKHits").value(1))
                .andExpect(jsonPath("$.precisionAtK").value(closeTo(0.5, 1e-9)))
                .andExpect(jsonPath("$.results[0].played").value(true))
                .andExpect(jsonPath("$.results[1].played").value(false))
                .andExpect(jsonPath("$.surprises[0].songName").value("Symphony of Destruction"));
    }

    /** 아직 실제 셋리스트가 연결되지 않은 이벤트의 적중률 조회는 404 Problem이다. */
    @Test
    void accuracyIsNotFoundBeforeVerification() throws Exception {
        TargetEvent event = persistEvent("미검증 이벤트", LocalDate.of(2026, 10, 2));

        mockMvc.perform(get("/api/events"))
                .andExpect(jsonPath("$[0].verified").value(false));
        mockMvc.perform(get("/api/events/{id}/accuracy", event.getId()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")));
    }

    @Test
    void unknownEventProducesProblemDetail() throws Exception {
        mockMvc.perform(get("/api/events/999999/predictions"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("리소스를 찾을 수 없습니다"))
                .andExpect(jsonPath("$.detail").value(containsString("999999")));
    }

    @Test
    void returnsArtistWithRecentShowStats() throws Exception {
        persistShow("api-s1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, 2);
        persistShow("api-s2", LocalDate.of(2026, 7, 10), ShowType.UNKNOWN, 3);
        persistShow("api-s3", LocalDate.of(2026, 7, 20), ShowType.UNKNOWN, 0); // 등록만 된 빈 셋리스트
        entityManager.flush();

        mockMvc.perform(get("/api/artists/{mbid}", artist.getMbid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mbid").value(artist.getMbid().toString()))
                .andExpect(jsonPath("$.name").value("Megadeth"))
                .andExpect(jsonPath("$.setlistFmUrl").value(containsString("setlist.fm")))
                .andExpect(jsonPath("$.recentShows.total").value(3))
                .andExpect(jsonPath("$.recentShows.festival").value(1))
                .andExpect(jsonPath("$.recentShows.latestEventDate").value("2026-07-20"))
                // 평균 곡 수는 빈 셋리스트를 제외하고 (2+3)/2 = 2.5
                .andExpect(jsonPath("$.recentShows.avgSongCount").value(closeTo(2.5, 1e-9)));
    }

    @Test
    void artistWithoutShowsHasEmptyStats() throws Exception {
        mockMvc.perform(get("/api/artists/{mbid}", artist.getMbid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentShows.total").value(0))
                // 통계 없음은 키 생략이 아니라 명시적 null이다(실측) — doesNotExist는 null도 통과시켜
                // 계약을 잘못 문서화하므로 nullValue로 못박는다
                .andExpect(jsonPath("$.recentShows.latestEventDate").value(nullValue()))
                .andExpect(jsonPath("$.recentShows.avgSongCount").value(nullValue()));
    }

    @Test
    void unknownArtistProducesProblemDetail() throws Exception {
        mockMvc.perform(get("/api/artists/{mbid}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * 경로 변수 형식 오류(UUID 아님)는 500이 아니라 400 Problem이어야 한다.
     * problemdetails.enabled가 프레임워크 수준에서 변환한다(커스텀 핸들러 불필요 — 실측으로 확인).
     */
    @Test
    void malformedMbidProducesBadRequestProblem() throws Exception {
        mockMvc.perform(get("/api/artists/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }
}
