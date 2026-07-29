package com.encore.setlist.client;

import java.util.List;

/**
 * setlist.fm의 setlist 객체 1건 = 공연 1회.
 * <p>
 * 문서 스키마 기준으로 작성했고 실응답 검증 전이다. 특히 tour / venue.city /
 * song.cover / song.tape는 존재가 보장되지 않으므로 전부 nullable로 두고,
 * 호출부가 널 체크를 반복하지 않도록 방어적 헬퍼를 함께 제공한다.
 */
public record SetlistDto(
        String id,
        String versionId,
        String eventDate,
        String lastUpdated,
        ArtistDto artist,
        Venue venue,
        Tour tour,
        Sets sets,
        String info,
        String url
) {

    /** tour는 자주 누락된다. 없으면 null. */
    public String tourName() {
        return tour != null ? tour.name() : null;
    }

    public String venueName() {
        return venue != null ? venue.name() : null;
    }

    public String cityName() {
        return venue != null ? venue.cityName() : null;
    }

    public String countryCode() {
        return venue != null ? venue.countryCode() : null;
    }

    /** sets 또는 sets.set이 없으면 빈 목록. */
    public List<SetEntry> songSets() {
        return sets != null ? sets.set() : List.of();
    }

    public record Tour(String name) {
    }

    public record Venue(String id, String name, City city) {

        /** city는 누락될 수 있다. */
        public String cityName() {
            return city != null ? city.name() : null;
        }

        public String countryCode() {
            return city != null && city.country() != null ? city.country().code() : null;
        }
    }

    public record City(String name, Country country) {
    }

    public record Country(String code, String name) {
    }

    public record Sets(List<SetEntry> set) {
        public Sets {
            set = set != null ? List.copyOf(set) : List.of();
        }
    }

    /** JSON 이름은 "set"이지만 java.util.Set과 헷갈리지 않게 타입명만 바꿨다. */
    public record SetEntry(String name, Integer encore, List<Song> song) {
        public SetEntry {
            song = song != null ? List.copyOf(song) : List.of();
        }

        /** encore 값(1..n)이 있으면 앙코르 셋이다. */
        public boolean isEncore() {
            return encore != null;
        }
    }

    public record Song(String name, ArtistDto cover, ArtistDto with, String info, Boolean tape) {

        /** tape가 없으면 실연주로 본다. true일 때만 집계에서 제외(CLAUDE.md). */
        public boolean isTape() {
            return Boolean.TRUE.equals(tape);
        }

        public boolean isCover() {
            return cover != null;
        }

        public String coverArtistName() {
            return cover != null ? cover.name() : null;
        }
    }
}
