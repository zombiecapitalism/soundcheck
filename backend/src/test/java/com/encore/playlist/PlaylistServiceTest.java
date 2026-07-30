package com.encore.playlist;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 재생목록 해석(E12) — 캐시·네거티브 캐시·예측 밖 곡 필터를 실제 DB로 검증. YouTube는 목. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PlaylistServiceTest {

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private PredictionRepository predictionRepository;
    @Autowired
    private SongVideoRepository songVideoRepository;

    private YoutubeClient youtubeClient;
    private PlaylistService service;
    private TargetEvent event;

    @BeforeEach
    void setUp() {
        youtubeClient = mock(YoutubeClient.class);
        service = new PlaylistService(predictionRepository, songVideoRepository, youtubeClient);

        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        event = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("이벤트").eventDate(LocalDate.of(2026, 10, 2))
                .expectedShowType(ShowType.FESTIVAL).build());
        predictionRepository.saveAllAndFlush(List.of(
                prediction(1, "holy wars", "Holy Wars"),
                prediction(2, "trust", "Trust")));
    }

    private Prediction prediction(int rank, String key, String name) {
        return Prediction.builder()
                .targetEvent(event).songKey(key).songName(name)
                .probability(new BigDecimal("0.9000")).rank((short) rank)
                .playedCount((short) 18).sampleSize((short) 20)
                .build();
    }

    @Test
    void resolvesViaSearchAndBuildsUrlInRequestedOrder() {
        when(youtubeClient.searchVideo(contains("Trust")))
                .thenReturn(Optional.of(new YoutubeClient.FoundVideo("vid-trust", "Trust (Live)")));
        when(youtubeClient.searchVideo(contains("Holy Wars")))
                .thenReturn(Optional.of(new YoutubeClient.FoundVideo("vid-holy", "Holy Wars")));

        PlaylistService.Playlist playlist =
                service.build(event, List.of("trust", "holy wars"));

        // 재생 순서 = 사용자가 고른 순서(rank 순이 아니다)
        assertThat(playlist.url())
                .isEqualTo("https://www.youtube.com/watch_videos?video_ids=vid-trust,vid-holy");
        assertThat(playlist.songs()).extracting(PlaylistService.Item::songKey)
                .containsExactly("trust", "holy wars");
        assertThat(playlist.missing()).isEmpty();
    }

    /** 두 번째 요청은 캐시로 해석 — 검색 API(쿼터 소모)를 다시 부르지 않는다. */
    @Test
    void servesFromCacheWithoutSecondSearch() {
        when(youtubeClient.searchVideo(anyString()))
                .thenReturn(Optional.of(new YoutubeClient.FoundVideo("vid-1", "제목")));

        service.build(event, List.of("holy wars"));
        service.build(event, List.of("holy wars"));

        verify(youtubeClient, times(1)).searchVideo(anyString());
    }

    /** 영상을 못 찾은 곡도 기록(네거티브 캐시) — 재검색하지 않고 missing으로 응답한다. */
    @Test
    void cachesNotFoundAndReportsMissing() {
        when(youtubeClient.searchVideo(anyString())).thenReturn(Optional.empty());

        PlaylistService.Playlist first = service.build(event, List.of("holy wars"));
        PlaylistService.Playlist second = service.build(event, List.of("holy wars"));

        assertThat(first.url()).isNull();
        assertThat(first.missing()).extracting(PlaylistService.Item::songKey)
                .containsExactly("holy wars");
        assertThat(second.missing()).hasSize(1);
        verify(youtubeClient, times(1)).searchVideo(anyString());
    }

    /** 예측 밖 곡은 검색 자체를 하지 않는다 — 임의 곡명으로 쿼터가 새면 안 된다. */
    @Test
    void ignoresKeysOutsidePredictions() {
        PlaylistService.Playlist playlist =
                service.build(event, List.of("no such song", "another fake"));

        assertThat(playlist.url()).isNull();
        assertThat(playlist.songs()).isEmpty();
        assertThat(playlist.missing()).isEmpty();
        verify(youtubeClient, never()).searchVideo(anyString());
    }
}
