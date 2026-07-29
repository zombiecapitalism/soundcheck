package com.encore.setlist;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 곡명 정규화(song_key 생성) — docs/setlist-schema.md 3장.
 * <p>
 * 정규화는 손실 변환이므로 결과는 집계 키로만 쓰고 원본(song_name)을 반드시 함께 저장한다.
 * <ol>
 *   <li>NFKC 정규화 (전각/반각 통일) 후 소문자 변환, 앞뒤 공백 제거</li>
 *   <li>괄호 부가정보 제거 — 화이트리스트 방식. (Live), (Acoustic), (Reprise) 등
 *       연주 형태 표기만 제거하고, (Part II)처럼 다른 곡을 구분하는 내용은 남긴다</li>
 *   <li>구두점 제거 후 공백 1칸으로 압축. 단 아포스트로피는 삭제(don't → dont)하고
 *       나머지는 공백 치환(Knife-Edge → knife edge) — 삭제로 통일하면 하이픈 표기와
 *       띄어쓰기 표기(knifeedge ≠ knife edge)가 서로 다른 키가 되기 때문</li>
 *   <li>선행 관사는 유지한다 (The Stage ≠ Stage인 경우가 있어 무리한 제거 금지)</li>
 * </ol>
 */
public final class SongKeys {

    /** 소괄호/대괄호 안 내용. 소문자 변환 후에 적용되므로 소문자 기준으로 매칭한다. */
    private static final Pattern BRACKETED = Pattern.compile("[(\\[]([^)\\]]*)[)\\]]");

    /**
     * 제거해도 같은 곡인 연주 형태 표기 화이트리스트.
     * 접두 단어 매칭이라 "(live at wembley)"도 제거되지만 "(lively)"는 남는다.
     */
    private static final Pattern VARIANT_MARKER = Pattern.compile(
            "^(live|acoustic|reprise|remaster(?:ed)?|demo|instrumental|unplugged)\\b.*");

    private static final Pattern APOSTROPHES = Pattern.compile("['’`´]");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private SongKeys() {
    }

    public static String normalize(String songName) {
        if (songName == null || songName.isBlank()) {
            throw new IllegalArgumentException("곡명이 비어 있습니다");
        }
        String base = Normalizer.normalize(songName, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();

        String result = removeVariantMarkers(base);
        result = APOSTROPHES.matcher(result).replaceAll("");
        result = PUNCTUATION.matcher(result).replaceAll(" ");
        result = SPACES.matcher(result).replaceAll(" ").strip();

        // 곡명이 통째로 구두점이면("?" 같은 제목) 키가 비어 모든 곡이 한 키로 뭉친다.
        // 이 경우만 구두점을 남긴 상태로 되돌린다.
        return result.isBlank() ? base : result;
    }

    private static String removeVariantMarkers(String name) {
        return BRACKETED.matcher(name).replaceAll(match ->
                VARIANT_MARKER.matcher(match.group(1).strip()).matches() ? "" : match.group());
    }
}
