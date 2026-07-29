package com.encore.setlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * show_type 판정용 수동 매핑. venue/tour명에 "festival" 키워드가 없는
 * 페스티벌 공연장(예: 삼락생태공원)을 운영자가 등록한다.
 */
@Entity
@Table(name = "festival_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FestivalMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** venue명 또는 tour명에 이 문자열이 포함되면(대소문자 무시) FESTIVAL로 판정한다. */
    @Column(name = "keyword", nullable = false, length = 300)
    private String keyword;

    public FestivalMapping(String keyword) {
        this.keyword = keyword;
    }
}
