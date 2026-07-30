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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    /** 곡 상세 — 예측 근거와 함께 최근 공연 타임라인(연주/미연주 모두)을 준다. */
    @Test
    void predictionDetailIncludesPlayHistoryTimeline() throws Exception {
        TargetEvent event = persistEvent("타임라인 이벤트", LocalDate.of(2026, 10, 2));
        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(event).songKey("holy wars").songName("Holy Wars")
                .probability(new BigDecimal("0.5000")).rank((short) 1)
                .playedCount((short) 1).sampleSize((short) 2)
                .build());
        // 최근 2회: 최신 공연에는 없음(미연주), 이전 공연에는 3번째 곡으로 연주
        persistShow("tl-miss", LocalDate.of(2026, 7, 20), ShowType.UNKNOWN, 0);
        Show missShow = entityManager.find(Show.class, "tl-miss");
        missShow.replaceSongs(List.of(
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                        .songName("Other Song").songKey("other song").build()));
        persistShow("tl-hit", LocalDate.of(2026, 7, 10), ShowType.FESTIVAL, 0);
        Show hitShow = entityManager.find(Show.class, "tl-hit");
        hitShow.replaceSongs(List.of(
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                        .songName("Opener").songKey("opener").build(),
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 2).positionTotal((short) 2)
                        .songName("Second").songKey("second").build(),
                ShowSong.builder().setIndex((short) 1).encore(true).positionInSet((short) 1)
                        .positionTotal((short) 3).songName("Holy Wars").songKey("holy wars").build()));
        entityManager.flush();

        mockMvc.perform(get("/api/events/{id}/predictions/{songKey}", event.getId(), "holy wars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prediction.songName").value("Holy Wars"))
                .andExpect(jsonPath("$.prediction.sampleSize").value(2))
                .andExpect(jsonPath("$.history.length()").value(2))
                // 최근순: 미연주 공연이 먼저
                .andExpect(jsonPath("$.history[0].setlistId").value("tl-miss"))
                .andExpect(jsonPath("$.history[0].played").value(false))
                .andExpect(jsonPath("$.history[0].position").value(nullValue()))
                .andExpect(jsonPath("$.history[1].setlistId").value("tl-hit"))
                .andExpect(jsonPath("$.history[1].played").value(true))
                .andExpect(jsonPath("$.history[1].position").value(3))
                .andExpect(jsonPath("$.history[1].encore").value(true))
                .andExpect(jsonPath("$.history[1].playedSongCount").value(3))
                .andExpect(jsonPath("$.history[1].showType").value("FESTIVAL"));

        mockMvc.perform(get("/api/events/{id}/predictions/{songKey}", event.getId(), "no such song"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")));
    }

    /**
     * 지난 이벤트의 타임라인은 공연일에서 잘린다 — 예측은 스냅샷으로 고정인데
     * 타임라인만 그 뒤 공연을 포함하면 근거 수치와 어긋난다.
     */
    @Test
    void predictionDetailCutsHistoryAtEventDate() throws Exception {
        TargetEvent event = persistEvent("지난 이벤트", LocalDate.of(2026, 7, 15));
        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(event).songKey("holy wars").songName("Holy Wars")
                .probability(new BigDecimal("1.0000")).rank((short) 1)
                .playedCount((short) 1).sampleSize((short) 5)
                .build());
        for (String[] spec : new String[][] {
                {"cut-before", "2026-07-10"}, {"cut-on", "2026-07-15"}, {"cut-after", "2026-07-20"}}) {
            persistShow(spec[0], LocalDate.parse(spec[1]), ShowType.UNKNOWN, 0);
            entityManager.find(Show.class, spec[0]).replaceSongs(List.of(
                    ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                            .songName("Holy Wars").songKey("holy wars").build()));
        }
        entityManager.flush();

        mockMvc.perform(get("/api/events/{id}/predictions/{songKey}", event.getId(), "holy wars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(2)) // 공연일 이후(cut-after) 제외
                .andExpect(jsonPath("$.history[0].setlistId").value("cut-on"))
                .andExpect(jsonPath("$.history[1].setlistId").value("cut-before"))
                // evidence 없이 저장된 스냅샷: 근거 블록은 null, 신뢰도는 표본 5회 < 8 → LOW
                .andExpect(jsonPath("$.confidence").value("LOW"))
                .andExpect(jsonPath("$.evidence").value(nullValue()))
                .andExpect(jsonPath("$.prediction.trend").value(nullValue()));
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
                // P=0.5, R=0.5(2곡 중 holy wars만 예측에 있음) → F1=0.5. Top-5 창은 예측 2곡뿐이라 분모 2
                .andExpect(jsonPath("$.f1").value(closeTo(0.5, 1e-9)))
                .andExpect(jsonPath("$.top5.size").value(2))
                .andExpect(jsonPath("$.top5.hits").value(1))
                .andExpect(jsonPath("$.top5.accuracy").value(closeTo(0.5, 1e-9)))
                .andExpect(jsonPath("$.results[0].played").value(true))
                .andExpect(jsonPath("$.results[1].played").value(false))
                .andExpect(jsonPath("$.surprises[0].songName").value("Symphony of Destruction"));
    }

    /** 적중률 아카이브 — 검증된 이벤트만, 최근 공연부터, 스냅샷 없는 이벤트는 제외. */
    @Test
    void accuracyArchiveListsVerifiedEventsOnly() throws Exception {
        // 검증 + 스냅샷 있음 → 포함
        TargetEvent graded = persistEvent("채점된 공연", LocalDate.of(2026, 7, 1));
        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(graded).songKey("holy wars").songName("Holy Wars")
                .probability(new BigDecimal("0.9000")).rank((short) 1)
                .playedCount((short) 18).sampleSize((short) 20)
                .build());
        persistShow("arc-s1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, 0);
        Show actual = entityManager.find(Show.class, "arc-s1");
        actual.replaceSongs(List.of(
                ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                        .songName("Holy Wars").songKey("holy wars").build()));
        graded.recordActualSetlist(actual);
        // 미검증(미래) → 제외
        persistEvent("미래 공연", LocalDate.of(2026, 10, 2));
        entityManager.flush();

        mockMvc.perform(get("/api/events/accuracy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventName").value("채점된 공연"))
                .andExpect(jsonPath("$[0].artistName").value("Megadeth"))
                .andExpect(jsonPath("$[0].topK").value(1))
                .andExpect(jsonPath("$[0].topKHits").value(1))
                .andExpect(jsonPath("$[0].precisionAtK").value(closeTo(1.0, 1e-9)));
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

    /** E6 예상 셋리스트 — 유형별 평균 곡 수만큼 뽑아 본편(위치순)/앙코르 블록으로 나눈다. */
    @Test
    void composesExpectedSetlistFromPredictions() throws Exception {
        TargetEvent event = persistEvent("2026 부산국제록페스티벌", LocalDate.of(2026, 10, 2));
        persistShow("exp-s1", LocalDate.of(2026, 6, 1), ShowType.FESTIVAL, 3); // 페스티벌 평균 3곡
        predictionRepository.saveAll(List.of(
                Prediction.builder()
                        .targetEvent(event).songKey("closer").songName("Closer")
                        .probability(new BigDecimal("0.9500")).rank((short) 1)
                        .playedCount((short) 19).sampleSize((short) 20)
                        .avgPosition(new BigDecimal("9.0")).encoreRatio(new BigDecimal("0.0000"))
                        .build(),
                Prediction.builder()
                        .targetEvent(event).songKey("opener song").songName("Opener Song")
                        .probability(new BigDecimal("0.9000")).rank((short) 2)
                        .playedCount((short) 18).sampleSize((short) 20)
                        .avgPosition(new BigDecimal("1.0")).encoreRatio(new BigDecimal("0.0000"))
                        .build(),
                Prediction.builder()
                        .targetEvent(event).songKey("encore song").songName("Encore Song")
                        .probability(new BigDecimal("0.8500")).rank((short) 3)
                        .playedCount((short) 17).sampleSize((short) 20)
                        .avgPosition(new BigDecimal("10.0")).encoreRatio(new BigDecimal("0.8000"))
                        .build(),
                Prediction.builder()
                        .targetEvent(event).songKey("left out").songName("Left Out")
                        .probability(new BigDecimal("0.3000")).rank((short) 4)
                        .playedCount((short) 6).sampleSize((short) 20)
                        .build()));
        entityManager.flush();

        mockMvc.perform(get("/api/events/{id}/expected-setlist", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedSongCount").value(3))
                // 본편은 평균 위치순(evidence 없어 오프너 고정은 생략), 앙코르는 별도 블록
                .andExpect(jsonPath("$.main.length()").value(2))
                .andExpect(jsonPath("$.main[0].songKey").value("opener song"))
                .andExpect(jsonPath("$.main[0].order").value(1))
                .andExpect(jsonPath("$.main[1].songKey").value("closer"))
                .andExpect(jsonPath("$.encore.length()").value(1))
                .andExpect(jsonPath("$.encore[0].songKey").value("encore song"))
                .andExpect(jsonPath("$.encore[0].order").value(3));
    }

    /** 예측 전이면 빈 블록 — 목록 API와 같은 "준비 중" 계약. */
    @Test
    void expectedSetlistIsEmptyBeforePredictions() throws Exception {
        TargetEvent event = persistEvent("예측 전 이벤트", LocalDate.of(2026, 10, 2));

        mockMvc.perform(get("/api/events/{id}/expected-setlist", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedSongCount").value(0))
                .andExpect(jsonPath("$.main.length()").value(0))
                .andExpect(jsonPath("$.encore.length()").value(0));
    }

    /** Chat(E8) 요청 검증 — 유효하지 않은 요청은 모델 호출 전에 400/404로 끊어야 한다(비용 가드). */
    @Test
    void chatRejectsInvalidRequestsBeforeModelCall() throws Exception {
        TargetEvent event = persistEvent("챗 이벤트", LocalDate.of(2026, 10, 2));
        String json = "application/json";

        // 마지막 메시지가 user가 아님
        mockMvc.perform(post("/api/events/{id}/chat", event.getId()).contentType(json)
                        .content("""
                                {"messages":[{"role":"assistant","content":"안녕"}]}"""))
                .andExpect(status().isBadRequest());
        // 이력 중간의 null content — 그대로 모델 메시지로 가면 NPE 500이 된다
        mockMvc.perform(post("/api/events/{id}/chat", event.getId()).contentType(json)
                        .content("""
                                {"messages":[{"role":"assistant","content":null},
                                             {"role":"user","content":"질문"}]}"""))
                .andExpect(status().isBadRequest());
        // 질문 길이 제한 초과
        mockMvc.perform(post("/api/events/{id}/chat", event.getId()).contentType(json)
                        .content("""
                                {"messages":[{"role":"user","content":"%s"}]}"""
                                .formatted("가".repeat(501))))
                .andExpect(status().isBadRequest());
        // 이력 메시지 길이 초과 — 질문만 제한하면 앞 이력으로 토큰 비용 가드가 뚫린다
        mockMvc.perform(post("/api/events/{id}/chat", event.getId()).contentType(json)
                        .content("""
                                {"messages":[{"role":"assistant","content":"%s"},
                                             {"role":"user","content":"질문"}]}"""
                                .formatted("가".repeat(2001))))
                .andExpect(status().isBadRequest());
        // 빈 메시지 목록
        mockMvc.perform(post("/api/events/{id}/chat", event.getId()).contentType(json)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
        // 존재하지 않는 이벤트
        mockMvc.perform(post("/api/events/999999/chat").contentType(json)
                        .content("""
                                {"messages":[{"role":"user","content":"질문"}]}"""))
                .andExpect(status().isNotFound());
    }

    /** 재생목록(E12) — 테스트 환경은 YouTube 키가 없어 503, 이벤트 없음은 404가 먼저다. */
    @Test
    void playlistIsUnavailableWithoutApiKey() throws Exception {
        TargetEvent event = persistEvent("재생목록 이벤트", LocalDate.of(2026, 10, 2));

        mockMvc.perform(post("/api/events/{id}/playlist", event.getId())
                        .contentType("application/json")
                        .content("{\"songKeys\":[\"holy wars\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")));

        mockMvc.perform(post("/api/events/999999/playlist")
                        .contentType("application/json")
                        .content("{\"songKeys\":[\"holy wars\"]}"))
                .andExpect(status().isNotFound());
    }

    /** E5 통계 시드: 연도·투어·유형이 갈리는 3회 공연. holy wars는 마지막 공연에서 tape다. */
    private void persistStatsShows() {
        record Spec(String id, LocalDate date, ShowType type, String tour, boolean tape) {
        }
        for (Spec spec : List.of(
                new Spec("stat-2025", LocalDate.of(2025, 5, 1), ShowType.SOLO, "Mega Tour", false),
                new Spec("stat-2026a", LocalDate.of(2026, 6, 1), ShowType.FESTIVAL, null, false),
                new Spec("stat-2026b", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, null, true))) {
            Show show = Show.builder()
                    .setlistId(spec.id()).versionId("v1").artist(artist).eventDate(spec.date())
                    .tourName(spec.tour()).showType(spec.type()).rawJson("{}")
                    .build();
            show.replaceSongs(List.of(
                    ShowSong.builder().setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                            .songName("Filler").songKey("filler").build(),
                    ShowSong.builder().setIndex((short) 0).positionInSet((short) 2).positionTotal((short) 2)
                            .songName("Holy Wars").songKey("holy wars").tape(spec.tape()).build()));
            entityManager.persist(show);
        }
        entityManager.flush();
    }

    /** E5 곡 통계 — 전체 수집 공연 대상 연도·투어·유형별 등장률. tape는 등장으로 안 센다. */
    @Test
    void songStatsAggregateYearTourAndType() throws Exception {
        persistStatsShows();

        mockMvc.perform(get("/api/artists/{mbid}/songs/{songKey}/stats", artist.getMbid(), "holy wars"))
                .andExpect(status().isOk())
                // 연도별: 2025는 1/1, 2026은 2회 중 1회(tape 공연은 분모만)
                .andExpect(jsonPath("$.yearly.length()").value(2))
                .andExpect(jsonPath("$.yearly[0].year").value(2025))
                .andExpect(jsonPath("$.yearly[0].playedShows").value(1))
                .andExpect(jsonPath("$.yearly[1].year").value(2026))
                .andExpect(jsonPath("$.yearly[1].totalShows").value(2))
                .andExpect(jsonPath("$.yearly[1].playedShows").value(1))
                // 투어별: 공연 수 내림차순 — 투어 없음(null) 묶음 2회가 먼저
                .andExpect(jsonPath("$.tours[0].tourName").value(nullValue()))
                .andExpect(jsonPath("$.tours[0].totalShows").value(2))
                .andExpect(jsonPath("$.tours[1].tourName").value("Mega Tour"))
                .andExpect(jsonPath("$.tours[1].playedShows").value(1))
                // 유형별: UNKNOWN 공연(tape)은 등장 0
                .andExpect(jsonPath("$.types.length()").value(3))
                .andExpect(jsonPath("$.types[?(@.showType=='SOLO')].playedShows").value(1))
                .andExpect(jsonPath("$.types[?(@.showType=='UNKNOWN')].playedShows").value(0));
    }

    /** 연주 기록이 전혀 없는 곡(또는 tape뿐)은 404 — 빈 통계로 위장하지 않는다. */
    @Test
    void songStatsIsNotFoundForUnknownSong() throws Exception {
        persistStatsShows();

        mockMvc.perform(get("/api/artists/{mbid}/songs/{songKey}/stats", artist.getMbid(), "no such song"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")));
    }

    /** E5 아티스트 통계 — 연도별 활동량과 유형 분포. */
    @Test
    void artistStatsSummarizeActivity() throws Exception {
        persistStatsShows();

        mockMvc.perform(get("/api/artists/{mbid}/stats", artist.getMbid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yearly[0].year").value(2025))
                .andExpect(jsonPath("$.yearly[0].showCount").value(1))
                .andExpect(jsonPath("$.yearly[1].showCount").value(2))
                .andExpect(jsonPath("$.yearly[1].avgSongCount").value(closeTo(2.0, 1e-9)))
                .andExpect(jsonPath("$.typeDistribution.festival").value(1))
                .andExpect(jsonPath("$.typeDistribution.solo").value(1))
                .andExpect(jsonPath("$.typeDistribution.unknown").value(1));
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
