package com.encore.setlist;

import com.encore.artist.Artist;
import com.encore.setlist.client.EventDates;
import com.encore.setlist.client.SetlistDto;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * setlist 1건을 show/show_song에 반영한다. 셋리스트 단위 트랜잭션 —
 * 한 건이 실패해도 나머지 수집이 살아남아야 PARTIAL 기록이 의미를 가진다.
 */
@Component
public class ShowUpserter {

    public enum Result { INSERTED, UPDATED, SKIPPED }

    private final ShowRepository showRepository;
    private final EntityManager entityManager;

    public ShowUpserter(ShowRepository showRepository, EntityManager entityManager) {
        this.showRepository = showRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public Result upsert(Artist artist, SetlistDto dto, String rawJson, ShowType showType) {
        Show existing = showRepository.findById(dto.id()).orElse(null);
        if (existing != null && existing.hasSameVersion(dto.versionId())) {
            return Result.SKIPPED;
        }

        Show incoming = toShow(artist, dto, rawJson, showType);
        List<ShowSong> songs = toSongs(dto);

        if (existing == null) {
            incoming.replaceSongs(songs);
            // save()는 ID가 이미 있는 엔티티에 merge를 태우므로(불필요한 SELECT + orphan 삭제 누락
            // 위험 — ShowRepositoryTest 참고) 신규는 persist로 직접 넣는다.
            entityManager.persist(incoming);
            return Result.INSERTED;
        }

        existing.refreshFrom(incoming);
        existing.classifyAs(showType);
        existing.replaceSongs(songs);
        return Result.UPDATED;
    }

    private Show toShow(Artist artist, SetlistDto dto, String rawJson, ShowType showType) {
        return Show.builder()
                .setlistId(dto.id())
                .versionId(dto.versionId())
                .artist(artist)
                .eventDate(EventDates.parse(dto.eventDate()))
                .tourName(dto.tourName())
                .venueName(dto.venueName())
                .cityName(dto.cityName())
                .countryCode(dto.countryCode())
                .showType(showType)
                .sourceUrl(dto.url())
                .rawJson(rawJson)
                .build();
    }

    private List<ShowSong> toSongs(SetlistDto dto) {
        List<ShowSong> songs = new ArrayList<>();
        short positionTotal = 0;
        List<SetlistDto.SetEntry> sets = dto.songSets();
        for (int setIndex = 0; setIndex < sets.size(); setIndex++) {
            SetlistDto.SetEntry set = sets.get(setIndex);
            short positionInSet = 0;
            for (SetlistDto.Song song : set.song()) {
                if (song.name() == null || song.name().isBlank()) {
                    continue; // 곡명 없는 항목은 집계 불가 — 원본은 raw_json에 남는다
                }
                positionInSet++;
                positionTotal++;
                songs.add(ShowSong.builder()
                        .setIndex((short) setIndex)
                        .encore(set.isEncore())
                        .positionInSet(positionInSet)
                        .positionTotal(positionTotal)
                        .songName(song.name())
                        .songKey(SongKeys.normalize(song.name()))
                        .cover(song.isCover())
                        .coverArtist(song.coverArtistName())
                        .tape(song.isTape()) // tape도 저장하고 플래그로만 구분 — 제외는 예측 단계의 몫
                        .note(song.info())
                        .build());
            }
        }
        return songs;
    }
}
