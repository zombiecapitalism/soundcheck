package com.encore.setlist;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.CollectionCounts;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.batch.JobType;
import com.encore.setlist.client.SetlistFmClient;
import com.encore.setlist.client.SetlistsPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 셋리스트 수집 배치 — docs/setlist-schema.md 4장 흐름.
 * 아티스트 단위로 최근 N회 공연을 수집하고 아티스트마다 collection_log 한 건을 남긴다.
 * 트랜잭션은 셋리스트 단위(ShowUpserter)라 한 건의 실패가 전체를 되돌리지 않는다.
 */
@Component
public class SetlistCollector {

    private static final Logger log = LoggerFactory.getLogger(SetlistCollector.class);

    private final ArtistRepository artistRepository;
    private final FestivalMappingRepository festivalMappingRepository;
    private final CollectionLogRepository collectionLogRepository;
    private final SetlistFmClient client;
    private final ShowUpserter upserter;
    private final int recentShowsLimit;

    public SetlistCollector(ArtistRepository artistRepository,
                            FestivalMappingRepository festivalMappingRepository,
                            CollectionLogRepository collectionLogRepository,
                            SetlistFmClient client,
                            ShowUpserter upserter,
                            @Value("${encore.collect.recent-shows-limit:40}") int recentShowsLimit) {
        this.artistRepository = artistRepository;
        this.festivalMappingRepository = festivalMappingRepository;
        this.collectionLogRepository = collectionLogRepository;
        this.client = client;
        this.upserter = upserter;
        this.recentShowsLimit = recentShowsLimit;
    }

    /** is_target = true 아티스트 전체를 순회한다. 한 아티스트의 실패는 다음 아티스트로 번지지 않는다. */
    public List<CollectionLog> collectAll() {
        List<Artist> targets = artistRepository.findByTargetTrue();
        log.info("셋리스트 수집 시작 — 대상 {}팀", targets.size());
        List<CollectionLog> results = new ArrayList<>();
        for (Artist artist : targets) {
            CollectionLog result;
            try {
                result = collect(artist);
            } catch (RuntimeException e) {
                // collect 내부에서 못 잡은 예외(로그 저장 실패 등)가 나머지 아티스트 수집을
                // 막으면 안 된다. FAILED로 남기고 다음으로 넘어간다.
                log.error("{} 수집 중 예상치 못한 오류 — 다음 아티스트로 계속", artist.getName(), e);
                result = saveFailedQuietly(artist, e);
                if (result == null) {
                    continue;
                }
            }
            log.info("{} — {} (fetched={}, updated={}, skipped={})", artist.getName(), result.getStatus(),
                    result.getCounts().getFetched(), result.getCounts().getUpdated(),
                    result.getCounts().getSkipped());
            results.add(result);
        }
        return results;
    }

    /** FAILED 기록 자체가 실패해도(DB 장애 등) 순회는 계속돼야 하므로 여기서는 로그만 남긴다. */
    private CollectionLog saveFailedQuietly(Artist artist, RuntimeException cause) {
        try {
            return collectionLogRepository.save(CollectionLog.failed(
                    JobType.SETLIST_SYNC, artist.getMbid(), "예상치 못한 오류: " + cause.getMessage(),
                    Instant.now()));
        } catch (RuntimeException logFailure) {
            log.error("{} collection_log 기록도 실패", artist.getName(), logFailure);
            return null;
        }
    }

    CollectionLog collect(Artist artist) {
        Instant startedAt = Instant.now();
        List<String> manualKeywords = festivalMappingRepository.findAll().stream()
                .map(FestivalMapping::getKeyword)
                .toList();

        List<SetlistsPage.Item> items = new ArrayList<>();
        String fetchError = null;
        try {
            fetchRecentInto(artist, items);
        } catch (RuntimeException e) {
            // 페이지 일부만 받은 채 죽어도, 받은 것까지는 반영하고 PARTIAL로 남긴다
            fetchError = "목록 조회 실패: " + e.getMessage();
        }
        if (items.isEmpty() && fetchError != null) {
            return collectionLogRepository.save(
                    CollectionLog.failed(JobType.SETLIST_SYNC, artist.getMbid(), fetchError, startedAt));
        }

        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        if (fetchError != null) {
            errors.add(fetchError);
        }
        for (SetlistsPage.Item item : items) {
            try {
                ShowType showType = ShowTypes.classify(
                        item.setlist().venueName(), item.setlist().tourName(), manualKeywords);
                switch (upserter.upsert(artist, item.setlist(), item.rawJson(), showType)) {
                    case INSERTED, UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                errors.add(item.setlist().id() + ": " + e.getMessage());
            }
        }

        CollectionCounts counts = CollectionCounts.builder()
                .fetched(items.size())
                .updated(updated)
                .skipped(skipped)
                .build();
        CollectionLog result = errors.isEmpty()
                ? CollectionLog.success(JobType.SETLIST_SYNC, artist.getMbid(), counts, startedAt)
                : CollectionLog.partial(JobType.SETLIST_SYNC, artist.getMbid(), counts,
                        String.join("; ", errors), startedAt);
        return collectionLogRepository.save(result);
    }

    /** 최근 공연부터 내려오므로, 목표 건수를 채우면 순회를 끊는다(docs 4장 2번). */
    private void fetchRecentInto(Artist artist, List<SetlistsPage.Item> into) {
        int page = 1;
        while (into.size() < recentShowsLimit) {
            SetlistsPage result;
            try {
                result = client.getArtistSetlists(artist.getMbid().toString(), page);
            } catch (RestClientResponseException e) {
                // 마지막 페이지 너머 요청에는 404가 온다 — 정상 종료다.
                // 단 첫 페이지의 404는 MBID 자체가 잘못됐다는 뜻이므로 소리 내며 실패해야 한다.
                if (e.getStatusCode().value() == 404 && page > 1) {
                    break;
                }
                throw e;
            }
            if (result.items().isEmpty()) {
                return;
            }
            into.addAll(result.items());
            if (result.total() != null && into.size() >= result.total()) {
                break;
            }
            page++;
        }
        while (into.size() > recentShowsLimit) {
            into.removeLast();
        }
    }
}
