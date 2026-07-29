package com.encore.setlist;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.batch.JobStatus;
import com.encore.common.config.SetlistFmProperties;
import com.encore.setlist.client.SetlistFmClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 수집 흐름 통합 테스트 — 실제 API 대신 MockRestServiceServer(CLAUDE.md).
 * ShowUpserter를 수동 조립하므로 셋리스트 단위 트랜잭션 분리는 여기서 검증하지 않고
 * (테스트 전체가 한 트랜잭션), 스킵/재적재/판정/로그 의미를 검증한다.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SetlistCollectorTest {

    private static final String BASE = "https://api.setlist.fm/rest";

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private ShowSongRepository showSongRepository;
    @Autowired
    private FestivalMappingRepository festivalMappingRepository;
    @Autowired
    private CollectionLogRepository collectionLogRepository;
    @Autowired
    private EntityManager entityManager;

    private MockRestServiceServer server;
    private SetlistCollector collector;
    private Artist artist;

    @BeforeEach
    void setUp() {
        collector = new SetlistCollector(artistRepository, festivalMappingRepository,
                collectionLogRepository, newClient(), new ShowUpserter(showRepository, entityManager), 40);
        artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Avenged Sevenfold").target(true).build());
    }

    private SetlistFmClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new SetlistFmClient(builder,
                new SetlistFmProperties(BASE, "test-key", Duration.ZERO, 0, Duration.ofMillis(1)),
                JsonMapper.builder().build());
    }

    private String setlistsUrl(int page) {
        return BASE + "/1.0/artist/" + artist.getMbid() + "/setlists?p=" + page;
    }

    /** 페스티벌 공연 1건(tape/cover/앙코르 포함) + 일반 공연 1건. */
    private String twoSetlistsPage(String versionA) {
        return """
                {"type":"setlists","itemsPerPage":20,"page":1,"total":2,"setlist":[
                  {"id":"aaa11111","versionId":"%s","eventDate":"01-08-2026",
                   "venue":{"id":"v1","name":"Pentaport Rock Festival Grounds",
                            "city":{"name":"Incheon","country":{"code":"KR","name":"South Korea"}}},
                   "tour":{"name":"Asia Tour 2026"},
                   "sets":{"set":[
                     {"song":[
                       {"name":"Entrance Tape","tape":true},
                       {"name":"Bat Country!"},
                       {"name":"  "},
                       {"name":"Walk","cover":{"mbid":"c1","name":"Pantera","sortName":"Pantera"}}
                     ]},
                     {"encore":1,"song":[{"name":"Save Me"}]}
                   ]},
                   "url":"https://www.setlist.fm/setlist/aaa11111.html"},
                  {"id":"bbb22222","versionId":"vb1","eventDate":"15-07-2026",
                   "venue":{"id":"v2","name":"Tokyo Dome","city":{"name":"Tokyo","country":{"code":"JP","name":"Japan"}}},
                   "sets":{"set":[{"song":[{"name":"Nightmare"}]}]},
                   "url":"https://www.setlist.fm/setlist/bbb22222.html"}
                ]}
                """.formatted(versionA);
    }

    private void expectPage(int page, String body) {
        server.expect(requestTo(setlistsUrl(page)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void insertsNewShowsWithClassificationAndNormalizedSongs() {
        expectPage(1, twoSetlistsPage("va1"));

        List<CollectionLog> logs = collector.collectAll();

        assertThat(logs).hasSize(1);
        CollectionLog log = logs.getFirst();
        assertThat(log.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(log.getArtistMbid()).isEqualTo(artist.getMbid());
        assertThat(log.getCounts().getFetched()).isEqualTo(2);
        assertThat(log.getCounts().getUpdated()).isEqualTo(2);
        assertThat(log.getCounts().getSkipped()).isZero();

        Show festival = showRepository.findById("aaa11111").orElseThrow();
        assertThat(festival.getShowType()).isEqualTo(ShowType.FESTIVAL);
        assertThat(festival.getEventDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(festival.getTourName()).isEqualTo("Asia Tour 2026");
        // 곡명 없는 항목은 걸러지고 tape 곡은 저장된다 — 제외는 예측 단계의 몫
        assertThat(festival.getSongCount()).isEqualTo((short) 4);
        List<ShowSong> songs = showSongRepository.findByShow_SetlistId("aaa11111");
        assertThat(songs).extracting(ShowSong::getSongKey)
                .containsExactlyInAnyOrder("entrance tape", "bat country", "walk", "save me");
        ShowSong tape = songs.stream().filter(ShowSong::isTape).findFirst().orElseThrow();
        assertThat(tape.getSongName()).isEqualTo("Entrance Tape");
        ShowSong cover = songs.stream().filter(ShowSong::isCover).findFirst().orElseThrow();
        assertThat(cover.getCoverArtist()).isEqualTo("Pantera");
        ShowSong encore = songs.stream().filter(ShowSong::isEncore).findFirst().orElseThrow();
        assertThat(encore.getSongKey()).isEqualTo("save me");
        assertThat(encore.getPositionTotal()).isEqualTo((short) 4);

        // raw_json에 원문이 남는다
        entityManager.flush();
        String raw = (String) entityManager
                .createNativeQuery("select raw_json::text from show where setlist_id = 'aaa11111'")
                .getSingleResult();
        assertThat(raw).contains("Pentaport Rock Festival Grounds");

        assertThat(showRepository.findById("bbb22222").orElseThrow().getShowType())
                .isEqualTo(ShowType.UNKNOWN);
    }

    @Test
    void skipsWhenVersionUnchanged() {
        expectPage(1, twoSetlistsPage("va1"));
        collector.collectAll();
        server.reset();

        expectPage(1, twoSetlistsPage("va1"));
        CollectionLog second = collector.collectAll().getFirst();

        assertThat(second.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(second.getCounts().getSkipped()).isEqualTo(2);
        assertThat(second.getCounts().getUpdated()).isZero();
    }

    @Test
    void reloadsWhenVersionChanged() {
        expectPage(1, twoSetlistsPage("va1"));
        collector.collectAll();
        server.reset();

        // aaa11111만 편집됨: versionId 변경 + 곡 교체 + 공연장이 일반 공연장으로 수정
        String edited = """
                {"type":"setlists","itemsPerPage":20,"page":1,"total":2,"setlist":[
                  {"id":"aaa11111","versionId":"va2","eventDate":"01-08-2026",
                   "venue":{"id":"v9","name":"Incheon Arena","city":{"name":"Incheon","country":{"code":"KR","name":"South Korea"}}},
                   "sets":{"set":[{"song":[{"name":"Afterlife"}]}]},
                   "url":"https://www.setlist.fm/setlist/aaa11111.html"},
                  {"id":"bbb22222","versionId":"vb1","eventDate":"15-07-2026",
                   "venue":{"id":"v2","name":"Tokyo Dome","city":{"name":"Tokyo","country":{"code":"JP","name":"Japan"}}},
                   "sets":{"set":[{"song":[{"name":"Nightmare"}]}]},
                   "url":"https://www.setlist.fm/setlist/bbb22222.html"}
                ]}
                """;
        expectPage(1, edited);
        CollectionLog second = collector.collectAll().getFirst();
        entityManager.flush();
        entityManager.clear();

        assertThat(second.getCounts().getUpdated()).isEqualTo(1);
        assertThat(second.getCounts().getSkipped()).isEqualTo(1);
        Show reloaded = showRepository.findById("aaa11111").orElseThrow();
        assertThat(reloaded.getVersionId()).isEqualTo("va2");
        assertThat(reloaded.getVenueName()).isEqualTo("Incheon Arena");
        assertThat(reloaded.getShowType()).isEqualTo(ShowType.UNKNOWN); // 재판정
        assertThat(reloaded.getTourName()).isNull(); // tour가 사라진 것도 반영
        assertThat(showSongRepository.findByShow_SetlistId("aaa11111"))
                .extracting(ShowSong::getSongKey)
                .containsExactly("afterlife"); // 기존 곡 삭제 후 재삽입
    }

    @Test
    void usesManualFestivalMapping() {
        festivalMappingRepository.saveAndFlush(new FestivalMapping("삼락생태공원"));
        String page = """
                {"type":"setlists","itemsPerPage":20,"page":1,"total":1,"setlist":[
                  {"id":"ccc33333","versionId":"vc1","eventDate":"02-10-2026",
                   "venue":{"id":"v3","name":"삼락생태공원","city":{"name":"Busan","country":{"code":"KR","name":"South Korea"}}},
                   "sets":{"set":[{"song":[{"name":"Symphony of Destruction"}]}]},
                   "url":"https://www.setlist.fm/setlist/ccc33333.html"}
                ]}
                """;
        expectPage(1, page);

        collector.collectAll();

        assertThat(showRepository.findById("ccc33333").orElseThrow().getShowType())
                .isEqualTo(ShowType.FESTIVAL);
    }

    /** 최근순으로 내려오므로 목표 건수를 채우면 다음 페이지를 요청하지 않는다. */
    @Test
    void stopsPagingAtRecentShowsLimit() {
        SetlistCollector limited = new SetlistCollector(artistRepository, festivalMappingRepository,
                collectionLogRepository, newClient(), new ShowUpserter(showRepository, entityManager), 3);

        expectPage(1, pageOf(1, 5, "p1a", "p1b"));
        expectPage(2, pageOf(2, 5, "p2a", "p2b"));
        // 3페이지 expectation 없음 — 요청하면 verify에서 실패한다

        CollectionLog log = limited.collectAll().getFirst();

        assertThat(log.getCounts().getFetched()).isEqualTo(3);
        assertThat(showRepository.count()).isEqualTo(3);
        server.verify();
    }

    /** total이 없을 때 마지막 페이지 너머의 404는 오류가 아니라 정상 종료다. */
    @Test
    void treatsNotFoundBeyondLastPageAsEndOfPaging() {
        expectPage(1, """
                {"type":"setlists","itemsPerPage":2,"page":1,"setlist":[
                  {"id":"nt100001","versionId":"v1","eventDate":"01-06-2026",
                   "venue":{"id":"v","name":"Anywhere"},
                   "sets":{"set":[{"song":[{"name":"Song"}]}]},"url":"https://x/nt100001"}
                ]}
                """);
        server.expect(requestTo(setlistsUrl(2))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        CollectionLog log = collector.collectAll().getFirst();

        assertThat(log.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(log.getCounts().getFetched()).isEqualTo(1);
        server.verify();
    }

    /** 첫 페이지의 404는 MBID 자체가 잘못됐다는 뜻 — 조용히 넘기지 않고 FAILED로 남긴다. */
    @Test
    void failsLoudlyWhenFirstPageIsNotFound() {
        server.expect(requestTo(setlistsUrl(1))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        CollectionLog log = collector.collectAll().getFirst();

        assertThat(log.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(log.getErrorMessage()).contains("목록 조회 실패");
    }

    /**
     * collect 내부에서 못 잡은 예외(collection_log 저장 실패 등)가 다른 아티스트 수집을
     * 막으면 안 된다. 첫 아티스트의 로그 저장이 계속 실패해도 두 번째 아티스트는 수집된다.
     */
    @Test
    void continuesToNextArtistWhenUnexpectedErrorOccurs() {
        Artist second = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());

        CollectionLogRepository failingLogRepository = mock(CollectionLogRepository.class);
        when(failingLogRepository.save(any(CollectionLog.class))).thenAnswer(invocation -> {
            CollectionLog saved = invocation.getArgument(0);
            if (artist.getMbid().equals(saved.getArtistMbid())) {
                throw new RuntimeException("collection_log 저장 실패 시뮬레이션");
            }
            return collectionLogRepository.save(saved);
        });

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        SetlistFmClient client = new SetlistFmClient(builder,
                new SetlistFmProperties(BASE, "test-key", Duration.ZERO, 0, Duration.ofMillis(1)),
                JsonMapper.builder().build());
        SetlistCollector isolated = new SetlistCollector(artistRepository, festivalMappingRepository,
                failingLogRepository, client, new ShowUpserter(showRepository, entityManager), 40);

        server.expect(requestTo(setlistsUrl(1))).andRespond(
                withSuccess(pageOf(1, 1, "iso00001"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/1.0/artist/" + second.getMbid() + "/setlists?p=1")).andRespond(
                withSuccess(pageOf(1, 1, "iso00002"), MediaType.APPLICATION_JSON));

        List<CollectionLog> logs = isolated.collectAll();

        // 첫 아티스트 로그는 저장 실패로 빠지지만, 두 번째 아티스트는 정상 수집·기록된다
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getArtistMbid()).isEqualTo(second.getMbid());
        assertThat(showRepository.findById("iso00001")).isPresent();
        assertThat(showRepository.findById("iso00002")).isPresent();
    }

    /** 한 건이 깨져도(잘못된 eventDate) 나머지는 반영되고 PARTIAL로 남는다. */
    @Test
    void recordsPartialWhenOneSetlistFails() {
        String page = """
                {"type":"setlists","itemsPerPage":20,"page":1,"total":2,"setlist":[
                  {"id":"bad00001","versionId":"v1","eventDate":"2026-08-01",
                   "venue":{"id":"v1","name":"Somewhere"},
                   "sets":{"set":[{"song":[{"name":"Song A"}]}]},
                   "url":"https://x/bad00001"},
                  {"id":"good0001","versionId":"v1","eventDate":"01-08-2026",
                   "venue":{"id":"v2","name":"Elsewhere"},
                   "sets":{"set":[{"song":[{"name":"Song B"}]}]},
                   "url":"https://x/good0001"}
                ]}
                """;
        expectPage(1, page);

        CollectionLog log = collector.collectAll().getFirst();

        assertThat(log.getStatus()).isEqualTo(JobStatus.PARTIAL);
        assertThat(log.getCounts().getFetched()).isEqualTo(2);
        assertThat(log.getCounts().getUpdated()).isEqualTo(1);
        assertThat(log.getErrorMessage()).contains("bad00001");
        assertThat(showRepository.findById("good0001")).isPresent();
        assertThat(showRepository.findById("bad00001")).isEmpty();
    }

    private String pageOf(int page, int total, String... ids) {
        StringBuilder entries = new StringBuilder();
        for (String id : ids) {
            if (!entries.isEmpty()) {
                entries.append(',');
            }
            entries.append("""
                    {"id":"%s","versionId":"v1","eventDate":"01-06-2026",
                     "venue":{"id":"v","name":"Anywhere"},
                     "sets":{"set":[{"song":[{"name":"Song"}]}]},
                     "url":"https://x/%s"}
                    """.formatted(id, id));
        }
        return """
                {"type":"setlists","itemsPerPage":2,"page":%d,"total":%d,"setlist":[%s]}
                """.formatted(page, total, entries);
    }
}
