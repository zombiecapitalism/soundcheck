package com.encore.setlist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SongKeysTest {

    @Test
    void lowercasesAndTrims() {
        assertThat(SongKeys.normalize("  Bat Country  ")).isEqualTo("bat country");
        assertThat(SongKeys.normalize("HAIL TO THE KING")).isEqualTo("hail to the king");
    }

    /** NFKC — 전각 영문/기호, 반각 가나가 표준형으로 통일돼야 표기 흔들림이 같은 키로 모인다. */
    @Test
    void appliesNfkcNormalization() {
        assertThat(SongKeys.normalize("ＢＡＴ ＣＯＵＮＴＲＹ")).isEqualTo("bat country");
        assertThat(SongKeys.normalize("Bat Country！")).isEqualTo("bat country");
        assertThat(SongKeys.normalize("ｱｲｳ")).isEqualTo("アイウ");
    }

    /** 화이트리스트 괄호 제거 — 연주 형태 표기는 같은 곡이다. */
    @Test
    void removesWhitelistedVariantMarkers() {
        assertThat(SongKeys.normalize("Unholy Confessions (Live)")).isEqualTo("unholy confessions");
        assertThat(SongKeys.normalize("So Far Away (Acoustic)")).isEqualTo("so far away");
        assertThat(SongKeys.normalize("Second Heartbeat (Reprise)")).isEqualTo("second heartbeat");
        assertThat(SongKeys.normalize("Afterlife (LIVE)")).isEqualTo("afterlife");
        assertThat(SongKeys.normalize("Nightmare [Demo]")).isEqualTo("nightmare");
        // 접두 단어 매칭: 뒤에 부가 설명이 붙어도 제거된다
        assertThat(SongKeys.normalize("Fiction (Live at Wembley)")).isEqualTo("fiction");
    }

    /** 화이트리스트 밖의 괄호 내용은 다른 곡을 구분할 수 있으므로 남긴다. */
    @Test
    void keepsNonWhitelistedParentheticalContent() {
        assertThat(SongKeys.normalize("The Stage (Part II)")).isEqualTo("the stage part ii");
        assertThat(SongKeys.normalize("Brompton Cocktail (Lively)")).isEqualTo("brompton cocktail lively");
        assertThat(SongKeys.normalize("The Stage (Part II)")).isNotEqualTo(SongKeys.normalize("The Stage"));
    }

    /**
     * 구두점 처리 — 아포스트로피는 삭제, 나머지는 공백 치환.
     * 삭제로 통일하면 "Knife-Edge"와 "Knife Edge"가 다른 키(knifeedge ≠ knife edge)가 된다.
     */
    @Test
    void normalizesPunctuation() {
        assertThat(SongKeys.normalize("Bat Country!")).isEqualTo("bat country");
        assertThat(SongKeys.normalize("Don't Cry")).isEqualTo("dont cry");
        // 스트레이트/컬리 아포스트로피 표기가 같은 키로 모인다
        assertThat(SongKeys.normalize("Don’t Cry")).isEqualTo(SongKeys.normalize("Don't Cry"));
        assertThat(SongKeys.normalize("Knife-Edge")).isEqualTo("knife edge");
        assertThat(SongKeys.normalize("Knife-Edge")).isEqualTo(SongKeys.normalize("Knife Edge"));
        assertThat(SongKeys.normalize("Hail to the King: Deathbat")).isEqualTo("hail to the king deathbat");
        assertThat(SongKeys.normalize("Breaking/Entering")).isEqualTo("breaking entering");
        assertThat(SongKeys.normalize("Knives  &  Pens")).isEqualTo("knives pens");
    }

    /** 선행 관사 유지 — The Stage ≠ Stage인 경우가 있어 무리한 제거 금지(스키마 문서 3장 5번). */
    @Test
    void keepsLeadingArticles() {
        assertThat(SongKeys.normalize("The Stage")).isEqualTo("the stage");
        assertThat(SongKeys.normalize("The Stage")).isNotEqualTo("stage");
        assertThat(SongKeys.normalize("A Little Piece of Heaven")).isEqualTo("a little piece of heaven");
    }

    /** 한글/일본어 곡명은 그대로 보존된다 (CJK는 구두점 아님). */
    @Test
    void preservesCjkTitles() {
        assertThat(SongKeys.normalize("소나기")).isEqualTo("소나기");
        assertThat(SongKeys.normalize("灰色の水曜日")).isEqualTo("灰色の水曜日");
        assertThat(SongKeys.normalize("オレンジ")).isEqualTo("オレンジ");
    }

    /** 곡명이 통째로 구두점이면 빈 키 대신 원형(소문자·NFKC)으로 폴백해 서로 뭉치지 않게 한다. */
    @Test
    void fallsBackWhenNormalizationEmptiesTheName() {
        assertThat(SongKeys.normalize("?")).isEqualTo("?");
        assertThat(SongKeys.normalize("!!!")).isEqualTo("!!!");
        assertThat(SongKeys.normalize("?")).isNotEqualTo(SongKeys.normalize("!!!"));
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> SongKeys.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SongKeys.normalize("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
