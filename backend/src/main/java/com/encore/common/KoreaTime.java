package com.encore.common;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 내한 공연 기준 서비스의 "오늘" — 서버 타임존이 아니라 KST가 기준이다.
 * 배치 스케줄(cron zone)과 날짜 경계 판정이 같은 기준을 쓰도록 한 곳에 둔다.
 */
public final class KoreaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KoreaTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
