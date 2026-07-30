package com.encore.playlist;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** watch_videos 링크 생성 — 형식·상한·빈 입력 거부. */
class PlaylistLinksTest {

    @Test
    void buildsCommaJoinedWatchVideosUrl() {
        assertThat(PlaylistLinks.watchVideosUrl(List.of("abc123", "def456")))
                .isEqualTo("https://www.youtube.com/watch_videos?video_ids=abc123,def456");
    }

    /** watch_videos는 50개까지만 받는다 — 초과분은 조용히 버리지 말고 앞에서 자른다(선택 순서 우선). */
    @Test
    void capsAtFiftyVideos() {
        List<String> ids = IntStream.range(0, 60).mapToObj(i -> "v" + i).toList();

        String url = PlaylistLinks.watchVideosUrl(ids);

        assertThat(url).contains("v0,").contains("v49").doesNotContain("v50");
    }

    @Test
    void rejectsEmptyList() {
        assertThatThrownBy(() -> PlaylistLinks.watchVideosUrl(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
