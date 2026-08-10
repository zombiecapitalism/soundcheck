package com.encore.setlist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수집된 전체 공연의 show_type을 현재 키워드 기준으로 다시 판정한다.
 *
 * 분류는 수집 시점에만 적용되고 versionId가 같으면 SKIPPED로 끝나기 때문에,
 * festival_mapping에 키워드를 나중에 추가해도 이미 수집된 공연은 낡은 판정으로
 * 남는다(실측: 후지록은 venue가 "GREEN STAGE"로 등록돼 기본 키워드에 걸리지 않았고,
 * 매핑을 추가해도 기존 행이 UNKNOWN 그대로였다). 이 배치가 그 공백을 메운다.
 *
 * 지난 이벤트의 예측 스냅샷은 건드리지 않는다 — 재판정 결과는 이후 재계산되는
 * 미래 이벤트의 예측에만 반영된다(튜닝 로그의 전향 원칙).
 */
@Component
public class ShowReclassifier {

    private static final Logger log = LoggerFactory.getLogger(ShowReclassifier.class);

    private final ShowRepository showRepository;
    private final FestivalMappingRepository festivalMappingRepository;

    public ShowReclassifier(ShowRepository showRepository,
                            FestivalMappingRepository festivalMappingRepository) {
        this.showRepository = showRepository;
        this.festivalMappingRepository = festivalMappingRepository;
    }

    /** 판정이 달라진 공연만 갱신하고 그 건수를 돌려준다. 키워드 삭제도 반영된다(FESTIVAL → UNKNOWN). */
    @Transactional
    public int reclassifyAll() {
        List<String> keywords = festivalMappingRepository.findAll().stream()
                .map(FestivalMapping::getKeyword)
                .toList();
        int changed = 0;
        for (Show show : showRepository.findAll()) {
            ShowType judged = ShowTypes.classify(show.getVenueName(), show.getTourName(), keywords);
            if (judged != show.getShowType()) {
                show.classifyAs(judged);
                changed++;
            }
        }
        log.info("공연 재분류 완료 — 변경 {}건 (키워드 {}개)", changed, keywords.size());
        return changed;
    }
}
