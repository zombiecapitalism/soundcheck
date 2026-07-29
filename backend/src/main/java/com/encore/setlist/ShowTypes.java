package com.encore.setlist;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * show_type 판정 — docs/setlist-schema.md 4장.
 * venue명/tour명에 페스티벌 키워드가 포함되거나 수동 매핑(festival_mapping) 키워드에
 * 걸리면 FESTIVAL, 아니면 UNKNOWN. SOLO 판정은 자동으로 내리지 않는다 — 페스티벌
 * 표기가 없는 공연이 단독 공연이라는 보장이 없기 때문이다.
 */
public final class ShowTypes {

    /** 내한 페스티벌은 한글 표기가 흔해서 둘 다 기본 키워드로 둔다. */
    private static final List<String> BUILT_IN_KEYWORDS = List.of("festival", "페스티벌");

    private ShowTypes() {
    }

    public static ShowType classify(String venueName, String tourName, Collection<String> manualKeywords) {
        String haystack = ((venueName == null ? "" : venueName) + " " + (tourName == null ? "" : tourName))
                .toLowerCase(Locale.ROOT);
        for (String keyword : BUILT_IN_KEYWORDS) {
            if (haystack.contains(keyword)) {
                return ShowType.FESTIVAL;
            }
        }
        for (String keyword : manualKeywords) {
            if (keyword != null && !keyword.isBlank()
                    && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return ShowType.FESTIVAL;
            }
        }
        return ShowType.UNKNOWN;
    }
}
