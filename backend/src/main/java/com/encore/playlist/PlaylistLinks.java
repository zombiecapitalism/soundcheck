package com.encore.playlist;

import java.util.List;

/**
 * YouTube 임시 재생목록 링크 — 순수 함수.
 * watch_videos는 로그인 없이 영상 ID 목록을 연속 재생 큐로 만들어 주는 엔드포인트다.
 * 비공식이지만 오래 유지되어 왔다 — 깨지면 이 한 곳만 바꾸면 된다.
 */
public final class PlaylistLinks {

    /** watch_videos가 받아주는 영상 수 상한. */
    public static final int MAX_VIDEOS = 50;

    private PlaylistLinks() {
    }

    public static String watchVideosUrl(List<String> videoIds) {
        if (videoIds.isEmpty()) {
            throw new IllegalArgumentException("영상 ID가 없으면 재생목록을 만들 수 없습니다");
        }
        List<String> capped = videoIds.size() > MAX_VIDEOS
                ? videoIds.subList(0, MAX_VIDEOS)
                : videoIds;
        return "https://www.youtube.com/watch_videos?video_ids=" + String.join(",", capped);
    }
}
