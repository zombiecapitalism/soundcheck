package com.encore.setlist.client;

/**
 * setlist.fm의 artist 객체. 검색 결과, setlist.artist, song.cover / song.with에서 공용.
 * mbid가 MusicBrainz 식별자다 — 이름을 키로 쓰지 않는다(CLAUDE.md).
 */
public record ArtistDto(
        String mbid,
        String name,
        String sortName,
        String url
) {
}
