package com.encore.prediction;

import com.encore.common.KoreaTime;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 공연이 끝난 이벤트에 수집된 실제 셋리스트를 연결한다(적중률 검증의 정답 채우기).
 * 예측 스냅샷은 이미 고정되어 있다 — 예측 배치는 지난 이벤트를 재계산하지 않는다.
 */
@Component
public class AccuracyService {

    private static final Logger log = LoggerFactory.getLogger(AccuracyService.class);

    private final TargetEventRepository targetEventRepository;
    private final ShowRepository showRepository;

    public AccuracyService(TargetEventRepository targetEventRepository, ShowRepository showRepository) {
        this.targetEventRepository = targetEventRepository;
        this.showRepository = showRepository;
    }

    /**
     * 지난 이벤트 중 미연결 건에 대해 같은 아티스트·공연일의 수집 공연을 찾아 연결한다.
     * 곡이 없는 공연(등록만 된 페이지)은 정답이 될 수 없으므로 건너뛴다 — 다음 수집에서
     * 곡이 채워지면 그때 연결된다. 반환값은 이번에 연결된 이벤트 수.
     */
    @Transactional
    public int matchPastEvents() {
        List<TargetEvent> unmatched =
                targetEventRepository.findByEventDateBeforeAndActualSetlistIsNull(KoreaTime.today());
        int matched = 0;
        for (TargetEvent event : unmatched) {
            // 같은 날 여러 건이면 실연주 곡이 가장 많은 것을 본 세트로 본다(실측: A7X 7/27에
            // 5곡짜리 별칭 세트와 13곡짜리 본 세트가 공존). 동수면 setlistId로 결정적 선택.
            Show actual = showRepository
                    .findByArtist_MbidAndEventDate(event.getArtist().getMbid(), event.getEventDate())
                    .stream()
                    .filter(show -> !show.playedSongs().isEmpty())
                    .max(Comparator.comparingInt((Show show) -> show.playedSongs().size())
                            .thenComparing(Show::getSetlistId))
                    .orElse(null);
            if (actual == null) {
                continue;
            }
            event.recordActualSetlist(actual);
            matched++;
            log.info("{} — 실제 셋리스트 연결: {} ({}곡)", event.getEventName(),
                    actual.getSetlistId(), actual.playedSongs().size());
        }
        return matched;
    }
}
