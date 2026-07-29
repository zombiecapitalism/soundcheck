package com.encore.setlist.client;

import com.encore.common.config.SetlistFmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 * fixture는 문서 스키마 기준이며, 실응답 1건 확보 후 검증 예정.
 */
class SetlistFmClientTest {

    private static final String BASE = "https://api.setlist.fm/rest";
    private static final String MBID = "24e1b53c-3085-4581-8472-0b0088d2508c";

    private MockRestServiceServer server;

    private SetlistFmClient newClient(Duration minInterval, int maxRetries) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new SetlistFmClient(builder, new SetlistFmProperties(
                BASE, "test-key", minInterval, maxRetries, Duration.ofMillis(1)));
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

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.artist())
                .extracting(ArtistDto::mbid)
                .containsExactly(MBID);
        server.verify();
    }

    @Test
    void parsesSetlistsWithDefensiveNullHandling() {
        SetlistFmClient client = newClient(Duration.ZERO, 0);
        server.expect(requestTo(BASE + "/1.0/artist/" + MBID + "/setlists?p=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("artist-setlists.json"), MediaType.APPLICATION_JSON));

        SetlistsResponse response = client.getArtistSetlists(MBID, 1);
        assertThat(response.setlist()).hasSize(2);

        SetlistDto full = response.setlist().get(0);
        assertThat(full.versionId()).isEqualTo("7be1aaa0");
        assertThat(EventDates.parse(full.eventDate())).isEqualTo(LocalDate.of(2025, 8, 2));
        assertThat(full.tourName()).isEqualTo("Life Is But a Dream... Tour");
        assertThat(full.cityName()).isEqualTo("Incheon");
        assertThat(full.countryCode()).isEqualTo("KR");
        assertThat(full.songSets()).hasSize(2);
        assertThat(full.songSets().get(1).isEncore()).isTrue();

        List<SetlistDto.Song> songs = full.songSets().get(0).song();
        assertThat(songs.get(0).isTape()).isTrue();
        assertThat(songs.get(1).isTape()).isFalse();
        assertThat(songs.get(2).isCover()).isTrue();
        assertThat(songs.get(2).coverArtistName()).isEqualTo("Pantera");
        assertThat(songs.get(3).with().name()).isEqualTo("Guest Violinist");

        // 문서상 존재가 불확실한 필드들이 빠져도 조용히 null/기본값으로 흡수돼야 한다.
        SetlistDto sparse = response.setlist().get(1);
        assertThat(sparse.tourName()).isNull();
        assertThat(sparse.cityName()).isNull();
        assertThat(sparse.countryCode()).isNull();
        assertThat(sparse.songSets()).hasSize(1);
        SetlistDto.Song plain = sparse.songSets().get(0).song().get(0);
        assertThat(plain.isTape()).isFalse();
        assertThat(plain.isCover()).isFalse();
        assertThat(plain.coverArtistName()).isNull();
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

        assertThat(client.searchArtists("Megadeth").total()).isEqualTo(1);
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
