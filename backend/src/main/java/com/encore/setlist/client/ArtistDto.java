package com.encore.setlist.client;

/**
 * setlist.fm의 artist 객체. 검색 결과, setlist.artist, song.cover / song.with에서 공용.
 * mbid가 MusicBrainz 식별자다 — 이름을 키로 쓰지 않는다(CLAUDE.md).
 * <p>
 * 2026-07-30 실측(100건 표본) 기준 다섯 필드 모두 항상 존재한다.
 * disambiguation은 검색 후보 구분에 필요하다 — 실측에서 "Megadeth" 검색의 첫 결과가
 * 본체가 아니라 "Blue Öyster Cult feat. Megadeth"였다. 이름만으로 본체를 고를 수 없다.
 */
public record ArtistDto(
        String mbid,
        String name,
        String sortName,
        String disambiguation,
        String url
) {
}
