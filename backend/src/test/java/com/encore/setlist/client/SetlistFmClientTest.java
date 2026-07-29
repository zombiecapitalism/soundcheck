package com.encore.setlist.client;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * 실제 API를 호출하지 않는다(CLAUDE.md) — MockRestServiceServer + fixture JSON.
 * <p>
 * search-artists.json / artist-setlists.json은 2026-07-30 실응답에서 발췌한 것이다.
 * 단, encore·with·city 없는 venue는 실응답 1페이지에 등장하지 않아 여전히 문서 기준
 * 가정이며, artist-setlists-doc-edge-cases.json(synthetic)으로 따로 검증한다.
 */
class SetlistFmClientTest {

    private static final String BASE = "https://api.setlist.fm/rest";
    private static final String MBID = "24e1b53c-3085-4581-8472-0b0088d2508c";

    private MockRestServiceServer server;

    private SetlistFmClient newClient(Duration minInterval, int maxRetries) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new SetlistFmClient(builder, new SetlistFmProperties(
                BASE, "test-key", minInterval, maxRetries, Duration.ofMillis(1)),
                JsonMapper.builder().build());
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("fixtures/setlistfm/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void sendsRequiredHeadersAndEncodesQuery() {
        SetlistFmClient client = newClient(Duration.ZERO, 0);
        server.expect(requestTo(BASE + "/1.0/search/artists?artistName=Avenged%20Sevenfold"))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess(fixture("search-artists.json"), MediaType.APPLICATION_JSON));

        ArtistSearchResponse response = client.searchArtists("Avenged Sevenfold");

        // 실응답: 동명 협업 프로젝트까지 5건이 내려오고, 본체가 첫 번째다.
        assertThat(response.total()).isEqualTo(5);
        assertThat(response.artist()).hasSize(5);
        assertThat(response.artist().getFirst().mbid()).isEqualTo(MBID);
        assertThat(response.artist().getFirst().name()).isEqualTo("Avenged Sevenfold");
        server.verify();
    }

    /** 2026-07-30 실응답 발췌 fixture — 실제 필드 형태(g-접두 versionId, coords 등 미매핑 필드 포함) 검증. */
    @Test
    void parsesRealResponseShape() {
        SetlistFmClient client = newClient(Duration.ZERO, 0);
        server.expect(requestTo(BASE + "/1.0/artist/" + MBID + "/setlists?p=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("artist-setlists.json"), MediaType.APPLICATION_JSON));

        SetlistsPage response = client.getArtistSetlists(MBID, 1);
        assertThat(response.total()).isEqualTo(1381);
        assertThat(response.items()).hasSize(3);

        // raw_json 보관 규칙: DTO에 매핑 안 된 필드(coords)까지 원문에 남아 있어야 재처리가 가능하다
        assertThat(response.items().get(0).rawJson()).contains("\"coords\"");

        // 1건째: tour + tape·cover 곡 + 이름 붙은 세트("STATICA")가 모두 있는 공연
        SetlistDto full = response.items().get(0).setlist();
        assertThat(full.versionId()).isEqualTo("g1302e9f5");
        assertThat(EventDates.parse(full.eventDate())).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(full.tourName()).isEqualTo("North American Tour 2026");
        assertThat(full.cityName()).isEqualTo("Shakopee");
        assertThat(full.countryCode()).isEqualTo("US");
        assertThat(full.songSets()).hasSize(2);
        assertThat(full.songSets().get(1).name()).isEqualTo("STATICA");

        SetlistDto.Song tapeCover = full.songSets().get(0).song().getFirst();
        assertThat(tapeCover.isTape()).isTrue();
        assertThat(tapeCover.isCover()).isTrue();
        assertThat(tapeCover.coverArtistName()).isEqualTo("The Smashing Pumpkins");

        // 2건째: tour 누락 (실응답 20건 중 3건이 누락 상태였다)
        SetlistDto noTour = response.items().get(1).setlist();
        assertThat(noTour.tourName()).isNull();
        assertThat(noTour.songSets().getFirst().song()).hasSize(13);
        assertThat(noTour.songSets().getFirst().song().getFirst().isTape()).isTrue();

        // 3건째: 등록만 되고 곡이 없는 셋리스트 (집계 제외 대상)
        SetlistDto empty = response.items().get(2).setlist();
        assertThat(empty.songSets()).isEmpty();
        server.verify();
    }

    /**
     * encore / with / city 없는 venue는 문서에는 있지만 실응답 1페이지에 등장하지 않았다.
     * 실물 확인 전까지 synthetic fixture로 매핑과 방어적 널 처리를 고정해둔다.
     */
    @Test
    void parsesDocumentedButUnobservedFieldsDefensively() {
        SetlistFmClient client = newClient(Duration.ZERO, 0);
        server.expect(requestTo(BASE + "/1.0/artist/" + MBID + "/setlists?p=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("artist-setlists-doc-edge-cases.json"), MediaType.APPLICATION_JSON));

        SetlistDto edge = client.getArtistSetlists(MBID, 1).items().getFirst().setlist();

        assertThat(edge.venueName()).isEqualTo("Venue Without City");
        assertThat(edge.cityName()).isNull();
        assertThat(edge.countryCode()).isNull();

        List<SetlistDto.Song> songs = edge.songSets().getFirst().song();
        assertThat(songs.getFirst().isTape()).isFalse();
        assertThat(songs.getFirst().isCover()).isFalse();
        assertThat(songs.get(1).with().name()).isEqualTo("Guest Artist");

        assertThat(edge.songSets().getFirst().isEncore()).isFalse();
        assertThat(edge.songSets().get(1).isEncore()).isTrue();
        assertThat(edge.songSets().get(1).encore()).isEqualTo(1);
        server.verify();
    }

    @Test
    void retriesOn429And5xxThenSucceeds() {
        SetlistFmClient client = newClient(Duration.ZERO, 3);
        String url = BASE + "/1.0/search/artists?artistName=Megadeth";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(url)).andRespond(
                withSuccess(fixture("search-artists.json"), MediaType.APPLICATION_JSON));

        assertThat(client.searchArtists("Megadeth").total()).isEqualTo(5);
        server.verify();
    }

    @Test
    void givesUpAfterMaxRetries() {
        SetlistFmClient client = newClient(Duration.ZERO, 1);
        String url = BASE + "/1.0/search/artists?artistName=Megadeth";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.searchArtists("Megadeth"))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    /** setlist.fm은 검색 결과 0건에 404를 준다(2026-07-30 실측). 오타 검색이 예외로 터지면 안 된다. */
    @Test
    void translatesSearchNotFoundIntoEmptyResult() {
        SetlistFmClient client = newClient(Duration.ZERO, 3);
        server.expect(requestTo(BASE + "/1.0/search/artists?artistName=zzz-no-such-band"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ArtistSearchResponse response = client.searchArtists("zzz-no-such-band");

        assertThat(response.total()).isZero();
        assertThat(response.artist()).isEmpty();
        server.verify(); // maxRetries=3이어도 404는 재시도 없이 1회로 끝나야 한다
    }

    /** 404 같은 일반 4xx는 재시도해도 결과가 같다 — 요청이 1회만 나가야 한다. */
    @Test
    void doesNotRetryOnNonRetryableClientError() {
        SetlistFmClient client = newClient(Duration.ZERO, 3);
        server.expect(requestTo(BASE + "/1.0/artist/" + MBID + "/setlists?p=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getArtistSetlists(MBID, 1))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void enforcesMinIntervalBetweenRequests() {
        SetlistFmClient client = newClient(Duration.ofMillis(200), 0);
        String url = BASE + "/1.0/search/artists?artistName=Megadeth";
        server.expect(requestTo(url)).andRespond(
                withSuccess(fixture("search-artists.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(
                withSuccess(fixture("search-artists.json"), MediaType.APPLICATION_JSON));

        long start = System.nanoTime();
        client.searchArtists("Megadeth");
        client.searchArtists("Megadeth");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 두 번째 요청은 최소 간격을 기다려야 한다. 스케줄러 오차를 감안해 여유를 둔다.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(150);
        server.verify();
    }
}
