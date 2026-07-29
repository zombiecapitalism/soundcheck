package com.encore.prediction;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.batch.JobStatus;
import com.encore.batch.JobType;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PredictionBatchTest {

    private static final LocalDate BUSAN = LocalDate.of(2026, 10, 2);

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private PredictionRepository predictionRepository;
    @Autowired
    private CollectionLogRepository collectionLogRepository;
    @Autowired
    private EntityManager entityManager;

    private PredictionBatch batch;
    private Artist artist;

    @BeforeEach
    void setUp() {
        PredictionGenerator generator = new PredictionGenerator(targetEventRepository, showRepository,
                predictionRepository, new PredictionProperties(20, 0.95, 1.5),
                JsonMapper.builder().build());
        batch = new PredictionBatch(targetEventRepository, collectionLogRepository, generator);
        artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
    }

    private void persistShow(String id, LocalDate date, ShowType type, ShowSong... songs) {
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .showType(type).rawJson("{}")
                .build();
        show.replaceSongs(List.of(songs));
        entityManager.persist(show);
    }

    private ShowSong song(String name, String key, int position, boolean encore, boolean tape) {
        return ShowSong.builder()
                .setIndex((short) (encore ? 1 : 0)).encore(encore)
                .positionInSet((short) position).positionTotal((short) position)
                .songName(name).songKey(key).tape(tape)
                .build();
    }

    private TargetEvent persistEvent(LocalDate date) {
        return targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("2026 부산국제록페스티벌").eventDate(date)
                .expectedShowType(ShowType.FESTIVAL)
                .build());
    }

    @Test
    void predictsAndPersistsRankedRowsWithEvidence() {
        persistShow("s1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL,
                song("Intro Tape", "intro tape", 1, false, true),
                song("Holy Wars", "holy wars", 2, false, false),
                song("Trust", "trust", 3, false, false));
        persistShow("s2", LocalDate.of(2026, 7, 10), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false),
                song("Symphony of Destruction", "symphony of destruction", 5, true, false));
        entityManager.flush();
        TargetEvent event = persistEvent(BUSAN);

        List<CollectionLog> logs = batch.predictUpcoming();

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(logs.getFirst().getJobType()).isEqualTo(JobType.PREDICT);
        assertThat(logs.getFirst().getCounts().getFetched()).isEqualTo(2); // 표본 공연 수
        assertThat(logs.getFirst().getCounts().getUpdated()).isEqualTo(3); // 저장한 곡 수

        entityManager.flush();
        entityManager.clear();
        List<Prediction> rows = predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
        assertThat(rows).hasSize(3); // tape 곡 제외
        Prediction top = rows.getFirst();
        assertThat(top.getSongKey()).isEqualTo("holy wars"); // 2/2 연주
        assertThat(top.getRank()).isEqualTo((short) 1);
        assertThat(top.getProbability()).isEqualByComparingTo("1.0000");
        assertThat(top.getPlayedCount()).isEqualTo((short) 2);
        assertThat(top.getSampleSize()).isEqualTo((short) 2);

        Prediction encore = rows.stream()
                .filter(p -> p.getSongKey().equals("symphony of destruction")).findFirst().orElseThrow();
        assertThat(encore.getEncoreRatio()).isEqualByComparingTo("1.0000");
        assertThat(encore.getAvgPosition()).isEqualByComparingTo("5.0");

        // evidence는 유효한 JSONB로 저장되고 계산 근거를 담는다
        String evidence = (String) entityManager.createNativeQuery(
                        "select evidence::text from prediction where target_event_id = :id and song_key = 'holy wars'")
                .setParameter("id", event.getId())
                .getSingleResult();
        assertThat(evidence).contains("\"appearances\"").contains("\"totalWeight\"")
                .contains("\"recencyDecay\"").contains("\"baseFrequency\"");
    }

    /** 재계산은 전체 교체 — 같은 song_key가 유니크 제약에 걸리지 않고 사라진 곡은 지워진다. */
    @Test
    void recomputationReplacesPreviousPredictions() {
        persistShow("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false),
                song("Dropped Song", "dropped song", 2, false, false));
        entityManager.flush();
        TargetEvent event = persistEvent(BUSAN);
        batch.predictUpcoming();
        entityManager.flush();

        // 공연이 수정되어 dropped song이 사라진 뒤 재계산
        Show show = showRepository.findById("s1").orElseThrow();
        show.replaceSongs(List.of(song("Holy Wars", "holy wars", 1, false, false)));
        entityManager.flush();

        batch.predictUpcoming();
        entityManager.flush();
        entityManager.clear();

        List<Prediction> rows = predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
        assertThat(rows).extracting(Prediction::getSongKey).containsExactly("holy wars");
    }

    /** 지난 공연은 재계산 대상이 아니다. */
    @Test
    void ignoresPastEvents() {
        persistShow("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false));
        entityManager.flush();
        TargetEvent past = persistEvent(LocalDate.of(2026, 1, 1));

        List<CollectionLog> logs = batch.predictUpcoming();

        assertThat(logs).isEmpty();
        assertThat(predictionRepository.findByTargetEvent_IdOrderByRankAsc(past.getId())).isEmpty();
    }

    /** 표본이 없는 이벤트는 FAILED로 남고, 다른 이벤트 예측은 계속된다. */
    @Test
    void failsLoudlyWithoutSampleAndContinues() {
        Artist noShows = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Empty Band").target(true).build());
        targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(noShows).eventName("표본 없는 이벤트").eventDate(BUSAN)
                .expectedShowType(ShowType.FESTIVAL)
                .build());
        persistShow("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false));
        entityManager.flush();
        persistEvent(BUSAN.plusDays(1));

        List<CollectionLog> logs = batch.predictUpcoming();

        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(CollectionLog::getStatus)
                .containsExactlyInAnyOrder(JobStatus.FAILED, JobStatus.SUCCESS);
        CollectionLog failed = logs.stream()
                .filter(l -> l.getStatus() == JobStatus.FAILED).findFirst().orElseThrow();
        assertThat(failed.getErrorMessage()).contains("집계할 공연이 없습니다");
        assertThat(failed.getArtistMbid()).isEqualTo(noShows.getMbid());
    }

    /**
     * 빈 셋리스트(등록만 된 미래 공연)는 표본 상한을 잡아먹지 않는다 —
     * limit 전에 걸러져 표본이 "집계 가능한 최근 N회"로 채워진다.
     */
    @Test
    void songlessShowsDoNotConsumeSampleSlots() {
        PredictionGenerator sampleOfOne = new PredictionGenerator(targetEventRepository, showRepository,
                predictionRepository, new PredictionProperties(1, 1.0, 1.0), JsonMapper.builder().build());
        PredictionBatch limitedBatch = new PredictionBatch(targetEventRepository, collectionLogRepository,
                sampleOfOne);
        persistShow("upcoming", LocalDate.of(2026, 9, 30), ShowType.FESTIVAL); // 곡 0건
        persistShow("played", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false));
        entityManager.flush();
        TargetEvent event = persistEvent(BUSAN);

        List<CollectionLog> logs = limitedBatch.predictUpcoming();
        entityManager.flush();
        entityManager.clear();

        // 표본 1회가 빈 공연이 아니라 실제 연주 공연으로 채워졌다
        assertThat(logs.getFirst().getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(logs.getFirst().getCounts().getFetched()).isEqualTo(1);
        List<Prediction> rows = predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
        assertThat(rows).extracting(Prediction::getSongKey).containsExactly("holy wars");
        assertThat(rows.getFirst().getProbability()).isEqualByComparingTo("1.0000");
    }

    /** 표본 상한: 최근 N회만 집계에 들어간다. */
    @Test
    void respectsSampleSizeLimit() {
        PredictionGenerator smallSample = new PredictionGenerator(targetEventRepository, showRepository,
                predictionRepository, new PredictionProperties(2, 1.0, 1.0), JsonMapper.builder().build());
        PredictionBatch limitedBatch = new PredictionBatch(targetEventRepository, collectionLogRepository,
                smallSample);
        persistShow("oldest", LocalDate.of(2026, 5, 1), ShowType.UNKNOWN,
                song("Ancient Song", "ancient song", 1, false, false));
        persistShow("mid", LocalDate.of(2026, 6, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false));
        persistShow("recent", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                song("Holy Wars", "holy wars", 1, false, false));
        entityManager.flush();
        TargetEvent event = persistEvent(BUSAN);

        limitedBatch.predictUpcoming();
        entityManager.flush();
        entityManager.clear();

        List<Prediction> rows = predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());
        // 표본 2회(recent, mid)에만 나온 곡이 남고, oldest에만 있던 곡은 집계 밖이다
        assertThat(rows).extracting(Prediction::getSongKey).containsExactly("holy wars");
        assertThat(rows.getFirst().getSampleSize()).isEqualTo((short) 2);
    }
}
