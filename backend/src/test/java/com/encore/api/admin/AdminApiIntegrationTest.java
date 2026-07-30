package com.encore.api.admin;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.BatchLock;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import com.encore.setlist.client.ArtistDto;
import com.encore.setlist.client.ArtistSearchResponse;
import com.encore.setlist.client.SetlistFmClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 API 통합 테스트 — 인증 경계와 등록·트리거 흐름. setlist.fm 클라이언트는 목이다(CLAUDE.md). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminApiIntegrationTest {

    private static final String USER = "admin";
    private static final String PASS = "test-admin-password"; // src/test/resources 더미 계정

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private com.encore.prediction.TargetEventRepository targetEventRepository;
    @Autowired
    private BatchLock batchLock;
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private SetlistFmClient setlistFmClient;

    @Test
    void adminEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/logs")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/batch/collect")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/logs").with(httpBasic(USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    /** 조회 API는 인증 없이 열려 있어야 한다 — 보안 도입이 공개 API를 잠그면 안 된다. */
    @Test
    void publicApiRemainsOpen() throws Exception {
        mockMvc.perform(get("/api/events")).andExpect(status().isOk());
    }

    @Test
    void searchMarksAlreadyRegisteredCandidates() throws Exception {
        UUID registered = UUID.randomUUID();
        artistRepository.saveAndFlush(Artist.builder().mbid(registered).name("Megadeth").build());
        when(setlistFmClient.searchArtists(anyString())).thenReturn(new ArtistSearchResponse(2, 30, 1, List.of(
                new ArtistDto(registered.toString(), "Megadeth", "Megadeth", "American thrash metal band", "url1"),
                new ArtistDto(UUID.randomUUID().toString(), "Megadeth UK", "Megadeth UK", "Megadeth tribute band", "url2"))));

        mockMvc.perform(get("/api/admin/artists/search").param("name", "Megadeth").with(httpBasic(USER, PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].alreadyRegistered").value(true))
                .andExpect(jsonPath("$[0].disambiguation").value("American thrash metal band"))
                .andExpect(jsonPath("$[1].alreadyRegistered").value(false));
    }

    @Test
    void registersCandidateAsCollectionTarget() throws Exception {
        UUID mbid = UUID.randomUUID();
        String body = """
                {"mbid":"%s","name":"Avenged Sevenfold","sortName":"Avenged Sevenfold",
                 "setlistFmUrl":"https://www.setlist.fm/setlists/a7x.html"}
                """.formatted(mbid);

        mockMvc.perform(post("/api/admin/artists").with(httpBasic(USER, PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.target").value(true));

        assertThat(artistRepository.findById(mbid).orElseThrow().isTarget()).isTrue();

        // 같은 후보를 다시 등록해도 중복 행이 아니라 갱신이다
        mockMvc.perform(post("/api/admin/artists").with(httpBasic(USER, PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(artistRepository.count()).isEqualTo(1);
    }

    @Test
    void createsEventAndRunsPredictionImmediately() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        Show show = Show.builder()
                .setlistId("adm-s1").versionId("v1").artist(artist)
                .eventDate(LocalDate.of(2026, 7, 1)).showType(ShowType.UNKNOWN).rawJson("{}")
                .build();
        show.replaceSongs(List.of(ShowSong.builder()
                .setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                .songName("Holy Wars").songKey("holy wars")
                .build()));
        entityManager.persist(show);
        entityManager.flush();

        String body = """
                {"artistMbid":"%s","eventName":"2026 부산국제록페스티벌","eventDate":"2026-10-02",
                 "venueName":"삼락생태공원","expectedShowType":"FESTIVAL"}
                """.formatted(artist.getMbid());

        mockMvc.perform(post("/api/admin/events").with(httpBasic(USER, PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.predictionStatus").value("SUCCESS"));

        // 같은 아티스트·날짜 재등록은 409 충돌
        mockMvc.perform(post("/api/admin/events").with(httpBasic(USER, PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")));
    }

    /** 예측 대상의 UNKNOWN 유형은 도메인이 거부한다 — 500이 아니라 400 Problem이어야 한다. */
    @Test
    void rejectsUnknownExpectedShowType() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        String body = """
                {"artistMbid":"%s","eventName":"이상한 이벤트","eventDate":"2026-10-02",
                 "expectedShowType":"UNKNOWN"}
                """.formatted(artist.getMbid());

        mockMvc.perform(post("/api/admin/events").with(httpBasic(USER, PASS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SOLO 또는 FESTIVAL")));
    }

    /** 내한 감지 — KR 미래 공연만 잡히고, 이미 이벤트로 등록된 것은 플래그로 구분된다. */
    @Test
    void detectsUpcomingKoreaShows() throws Exception {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Pixies").target(true).build());
        persistShow(artist, "kr-future", LocalDate.of(2099, 8, 1), "KR", "인천 펜타포트 공원");
        persistShow(artist, "kr-registered", LocalDate.of(2099, 10, 2), "KR", "삼락생태공원");
        persistShow(artist, "kr-past", LocalDate.of(2020, 1, 1), "KR", "예전 공연장");
        persistShow(artist, "us-future", LocalDate.of(2099, 9, 1), "US", "Some Arena");
        targetEventRepository.saveAndFlush(com.encore.prediction.TargetEvent.builder()
                .artist(artist).eventName("이미 등록된 내한").eventDate(LocalDate.of(2099, 10, 2))
                .expectedShowType(com.encore.setlist.ShowType.FESTIVAL).build());
        entityManager.flush();

        mockMvc.perform(get("/api/admin/korea-shows").with(httpBasic(USER, PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)) // 과거 KR·미래 US 제외
                .andExpect(jsonPath("$[0].setlistId").value("kr-future"))
                .andExpect(jsonPath("$[0].artistName").value("Pixies"))
                .andExpect(jsonPath("$[0].venueName").value("인천 펜타포트 공원"))
                .andExpect(jsonPath("$[0].alreadyRegistered").value(false))
                .andExpect(jsonPath("$[1].setlistId").value("kr-registered"))
                .andExpect(jsonPath("$[1].alreadyRegistered").value(true));
    }

    private void persistShow(Artist artist, String id, LocalDate date, String countryCode, String venue) {
        entityManager.persist(Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .venueName(venue).countryCode(countryCode)
                .showType(ShowType.UNKNOWN).rawJson("{}")
                .build());
    }

    @Test
    void collectConflictsWhileAlreadyRunning() throws Exception {
        assertThat(batchLock.tryAcquireCollect()).isTrue();
        try {
            mockMvc.perform(post("/api/admin/batch/collect").with(httpBasic(USER, PASS)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("이미 실행 중"));
        } finally {
            batchLock.releaseCollect();
        }
    }

    @Test
    void collectStartsInBackgroundAndReleasesLock() throws Exception {
        mockMvc.perform(post("/api/admin/batch/collect").with(httpBasic(USER, PASS)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.started").value(true));

        // 백그라운드 수집(목 클라이언트, 대상 0팀)이 끝나면 락이 풀려야 한다
        for (int i = 0; i < 50 && batchLock.isCollecting(); i++) {
            Thread.sleep(100);
        }
        assertThat(batchLock.isCollecting()).isFalse();

        mockMvc.perform(get("/api/admin/logs").with(httpBasic(USER, PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collecting").value(false));
    }
}
