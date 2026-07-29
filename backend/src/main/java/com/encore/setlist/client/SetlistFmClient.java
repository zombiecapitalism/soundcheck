package com.encore.setlist.client;

import com.encore.common.config.SetlistFmProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * setlist.fm REST 클라이언트.
 * <ul>
 *   <li>인증은 x-api-key 헤더. Accept: application/json이 없으면 XML이 내려오므로 필수(CLAUDE.md).</li>
 *   <li>rate limit 대응: 요청 간 최소 간격을 강제하고, 429/5xx는 지수 백오프로 재시도한다.
 *       그 외 4xx는 재시도해도 결과가 같으므로 즉시 실패시킨다.</li>
 * </ul>
 */
@Component
public class SetlistFmClient {

    private final RestClient restClient;
    private final SetlistFmProperties properties;
    private final ObjectMapper objectMapper;

    /** 마지막 요청 시각(nanoTime). 최소 간격 계산용이며 아직 요청이 없으면 null. */
    private Long lastRequestAtNanos;

    public SetlistFmClient(RestClient.Builder restClientBuilder, SetlistFmProperties properties,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 아티스트 검색 — MBID 확보용. */
    public ArtistSearchResponse searchArtists(String artistName) {
        return executeWithRetry(() -> restClient.get()
                .uri("/1.0/search/artists?artistName={name}", artistName)
                .retrieve()
                .body(ArtistSearchResponse.class));
    }

    /**
     * 아티스트별 셋리스트 목록 — 최근 공연부터, p는 1부터 시작.
     * raw_json 보관 규칙 때문에 원문을 먼저 받아 트리로 쪼갠 뒤, 항목별로
     * 원본 노드 문자열과 DTO를 함께 돌려준다.
     */
    public SetlistsPage getArtistSetlists(String mbid, int page) {
        String body = executeWithRetry(() -> restClient.get()
                .uri("/1.0/artist/{mbid}/setlists?p={page}", mbid, page)
                .retrieve()
                .body(String.class));

        JsonNode root = objectMapper.readTree(body);
        List<SetlistsPage.Item> items = new ArrayList<>();
        for (JsonNode node : root.path("setlist")) {
            items.add(new SetlistsPage.Item(
                    objectMapper.treeToValue(node, SetlistDto.class), node.toString()));
        }
        return new SetlistsPage(
                intOrNull(root, "total"), intOrNull(root, "itemsPerPage"), intOrNull(root, "page"), items);
    }

    private static Integer intOrNull(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isNumber() ? node.asInt() : null;
    }

    private <T> T executeWithRetry(Supplier<T> request) {
        int attempt = 0;
        while (true) {
            attempt++;
            awaitMinInterval();
            try {
                return request.get();
            } catch (RestClientResponseException e) {
                if (!isRetryable(e.getStatusCode()) || attempt > properties.maxRetries()) {
                    throw e;
                }
                sleep(backoffFor(attempt));
            }
        }
    }

    private static boolean isRetryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    /** attempt번째 실패 후 대기 시간: initialBackoff × 2^(attempt-1). */
    private Duration backoffFor(int attempt) {
        return properties.initialBackoff().multipliedBy(1L << (attempt - 1));
    }

    /**
     * 직전 요청으로부터 최소 간격이 지날 때까지 대기한다.
     * 수집 배치는 단일 스레드지만, 혹시 모를 동시 호출에도 간격이 깨지지 않게 동기화한다.
     */
    private synchronized void awaitMinInterval() {
        long minNanos = properties.minRequestInterval().toNanos();
        if (lastRequestAtNanos != null && minNanos > 0) {
            long waitNanos = lastRequestAtNanos + minNanos - System.nanoTime();
            if (waitNanos > 0) {
                sleep(Duration.ofNanos(waitNanos));
            }
        }
        lastRequestAtNanos = System.nanoTime();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("setlist.fm 요청 대기 중 인터럽트", e);
        }
    }
}
