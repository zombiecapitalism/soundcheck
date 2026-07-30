package com.encore.playlist;

import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 예측 곡 묶음 듣기(E12) — 선택 곡을 YouTube 영상으로 해석해 임시 재생목록 링크를 만든다.
 * <p>
 * 비용 가드: 예측 목록에 있는 곡만 검색한다(임의 곡명으로 쿼터 유출 방지). 검색 결과는
 * 실패까지 song_video에 캐시되어 아티스트당 레퍼토리 수만큼만 쿼터를 쓴다.
 * 외부 API 호출이 있으므로 의도적으로 트랜잭션이 없다 — 캐시 저장은 곡 단위 자체 트랜잭션.
 */
@Service
public class PlaylistService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);

    public record Item(String songKey, String songName, String videoTitle) {
    }

    /** url null = 해석된 영상이 하나도 없음. missing = 영상을 못 찾은 곡(네거티브 캐시 포함). */
    public record Playlist(String url, List<Item> songs, List<Item> missing) {
    }

    private final PredictionRepository predictionRepository;
    private final SongVideoRepository songVideoRepository;
    private final YoutubeClient youtubeClient;

    public PlaylistService(PredictionRepository predictionRepository,
                           SongVideoRepository songVideoRepository, YoutubeClient youtubeClient) {
        this.predictionRepository = predictionRepository;
        this.songVideoRepository = songVideoRepository;
        this.youtubeClient = youtubeClient;
    }

    /** event는 artist가 fetch join으로 로드된 상태를 전제한다(검색 질의에 이름 사용). */
    public Playlist build(TargetEvent event, List<String> requestedKeys) {
        // 요청 순서를 유지한 채 중복 제거 — 재생 순서가 사용자가 고른 순서다
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (Prediction prediction :
                predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId())) {
            nameByKey.put(prediction.getSongKey(), prediction.getSongName());
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(requestedKeys)).stream()
                .filter(nameByKey::containsKey) // 예측 밖 곡은 조용히 제외 — 쿼터 유출 방지
                .limit(PlaylistLinks.MAX_VIDEOS)
                .toList();

        Map<String, SongVideo> cached = new LinkedHashMap<>();
        for (SongVideo video :
                songVideoRepository.findByArtistMbidAndSongKeyIn(event.getArtist().getMbid(), keys)) {
            cached.put(video.getSongKey(), video);
        }

        List<String> videoIds = new ArrayList<>();
        List<Item> songs = new ArrayList<>();
        List<Item> missing = new ArrayList<>();
        for (String key : keys) {
            String songName = nameByKey.get(key);
            SongVideo video = cached.get(key);
            if (video == null) {
                video = resolveAndCache(event.getArtist().getMbid(),
                        event.getArtist().getName(), key, songName);
            }
            if (video.getVideoId() != null) {
                videoIds.add(video.getVideoId());
                songs.add(new Item(key, songName, video.getVideoTitle()));
            } else {
                missing.add(new Item(key, songName, null));
            }
        }

        String url = videoIds.isEmpty() ? null : PlaylistLinks.watchVideosUrl(videoIds);
        return new Playlist(url, List.copyOf(songs), List.copyOf(missing));
    }

    private SongVideo resolveAndCache(UUID artistMbid, String artistName,
                                      String songKey, String songName) {
        Optional<YoutubeClient.FoundVideo> found;
        try {
            found = youtubeClient.searchVideo(artistName + " " + songName);
        } catch (RestClientException e) {
            // 쿼터 초과·네트워크 오류 등 일시 실패: 이 곡만 missing으로 응답하고(부분 성공),
            // 네거티브 캐시하지 않는다 — 복구되면 다음 요청에서 다시 시도돼야 한다.
            // 예외 메시지는 로그에 남기지 않는다 — ResourceAccessException 메시지에는
            // API 키가 포함된 요청 URL이 들어갈 수 있다.
            log.warn("YouTube 검색 실패({}) — 이번 요청에서만 missing 처리, 캐시 안 함: {}",
                    e.getClass().getSimpleName(), songName);
            return SongVideo.builder().artistMbid(artistMbid).songKey(songKey).build();
        }
        SongVideo video = SongVideo.builder()
                .artistMbid(artistMbid)
                .songKey(songKey)
                .videoId(found.map(YoutubeClient.FoundVideo::videoId).orElse(null))
                // 컬럼 VARCHAR(300) — API의 HTML 이스케이프된 제목이 넘칠 수 있어 잘라 저장
                .videoTitle(found.map(YoutubeClient.FoundVideo::title)
                        .map(title -> title.length() > 300 ? title.substring(0, 300) : title)
                        .orElse(null))
                .build();
        try {
            return songVideoRepository.save(video);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 같은 곡을 먼저 저장했다 — 그 결과를 쓰면 된다
            return songVideoRepository
                    .findByArtistMbidAndSongKeyIn(artistMbid, List.of(songKey)).stream()
                    .findFirst()
                    .orElseGet(() -> {
                        // 유니크 충돌이 아닌 무결성 위반(예상 밖) — 조용히 넘기면 쿼터 누수가 된다
                        log.warn("song_video 저장 실패 후 재조회도 실패(캐시 안 됨): {} / {}",
                                artistMbid, songKey, e);
                        return video;
                    });
        }
    }
}
