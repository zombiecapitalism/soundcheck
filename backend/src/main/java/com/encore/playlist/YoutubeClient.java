package com.encore.playlist;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * YouTube Data API v3 검색 — 곡당 대표 영상 1건을 찾는다(E12).
 * search.list는 호출당 100 쿼터 유닛(일 10,000 기본)이라 결과는 song_video에 캐시된다.
 */
@Component
public class YoutubeClient {

    public record FoundVideo(String videoId, String title) {
    }

    private final RestClient restClient;
    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;

    public YoutubeClient(RestClient.Builder restClientBuilder, YoutubeProperties properties,
                         ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 관련도 1위 영상. 없으면 empty — 호출자는 네거티브 캐시로 기록한다. */
    public Optional<FoundVideo> searchVideo(String query) {
        String body = restClient.get()
                .uri("/search?part=snippet&type=video&maxResults=1&q={q}&key={key}",
                        query, properties.apiKey())
                .retrieve()
                .body(String.class);
        JsonNode items = objectMapper.readTree(body).path("items");
        if (items.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = items.get(0);
        String videoId = first.path("id").path("videoId").asString("");
        if (videoId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new FoundVideo(videoId, first.path("snippet").path("title").asString("")));
    }
}
