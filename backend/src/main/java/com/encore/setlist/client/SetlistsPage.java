package com.encore.setlist.client;

import java.util.List;

/**
 * GET /1.0/artist/{mbid}/setlists 응답 한 페이지. 최근 공연부터 내려온다.
 * <p>
 * 항목마다 파싱된 DTO와 함께 응답 원문(setlist 노드)을 그대로 보관한다 —
 * raw_json 저장은 도메인 규칙(CLAUDE.md 7번)이라 DTO 재직렬화로 대체할 수 없다.
 * DTO에 매핑 안 된 필드(coords 등)가 재직렬화에서 유실되기 때문이다.
 */
public record SetlistsPage(
        Integer total,
        Integer itemsPerPage,
        Integer page,
        List<Item> items
) {

    public SetlistsPage {
        items = items != null ? List.copyOf(items) : List.of();
    }

    public record Item(SetlistDto setlist, String rawJson) {
    }
}
