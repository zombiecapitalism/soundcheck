package com.encore.api.admin;

import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 — 내한 자동 감지. 별도 크롤링 없이 수집 데이터에서 KR 미래 공연을 찾는다.
 * 한계: 수집 대상으로 등록된 아티스트의 내한만 잡힌다(UI에도 명시).
 */
@RestController
@RequestMapping("/api/admin/korea-shows")
public class AdminKoreaShowController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ShowRepository showRepository;
    private final TargetEventRepository targetEventRepository;

    public AdminKoreaShowController(ShowRepository showRepository,
                                    TargetEventRepository targetEventRepository) {
        this.showRepository = showRepository;
        this.targetEventRepository = targetEventRepository;
    }

    @GetMapping
    public List<KoreaShow> upcomingKoreaShows() {
        return showRepository.findUpcomingKoreaShows(LocalDate.now(KST)).stream()
                .map(show -> new KoreaShow(
                        show.getSetlistId(),
                        show.getArtist().getMbid(),
                        show.getArtist().getName(),
                        show.getEventDate(),
                        show.getVenueName(),
                        show.getCityName(),
                        show.getShowType(),
                        // 이벤트 유니크 키(artist, date)와 같은 기준으로 등록 여부를 판단한다
                        targetEventRepository.existsByArtist_MbidAndEventDate(
                                show.getArtist().getMbid(), show.getEventDate())))
                .toList();
    }

    public record KoreaShow(String setlistId, UUID artistMbid, String artistName, LocalDate eventDate,
                            String venueName, String cityName, ShowType showType,
                            boolean alreadyRegistered) {
    }
}
