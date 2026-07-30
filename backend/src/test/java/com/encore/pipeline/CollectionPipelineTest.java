package com.encore.pipeline;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.BatchLock;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.prediction.AccuracyService;
import com.encore.prediction.PredictionBatch;
import com.encore.prediction.PredictionGenerator;
import com.encore.prediction.PredictionProperties;
import com.encore.prediction.PredictionRepository;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.setlist.SetlistCollector;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 파이프라인 체인 검증 — "예측 재계산" 한 번으로 지난 이벤트 매칭과 다가오는 이벤트
 * 재계산이 함께 일어나야 한다(수집기·executor는 이 경로에서 안 쓰이므로 목).
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CollectionPipelineTest {

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

    @Test
    void matchAndPredictLinksPastEventAndRecomputesUpcoming() {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        Show pastShow = Show.builder()
                .setlistId("pl-past").versionId("v1").artist(artist)
                .eventDate(LocalDate.of(2026, 7, 1)).showType(ShowType.UNKNOWN).rawJson("{}")
                .build();
        pastShow.replaceSongs(List.of(ShowSong.builder()
                .setIndex((short) 0).positionInSet((short) 1).positionTotal((short) 1)
                .songName("Holy Wars").songKey("holy wars").build()));
        entityManager.persist(pastShow);
        TargetEvent pastEvent = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("지난 공연").eventDate(LocalDate.of(2026, 7, 1))
                .expectedShowType(ShowType.SOLO).build());
        TargetEvent upcomingEvent = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("다가오는 공연").eventDate(LocalDate.of(2099, 10, 2))
                .expectedShowType(ShowType.FESTIVAL).build());
        entityManager.flush();

        CollectionPipeline pipeline = new CollectionPipeline(
                mock(SetlistCollector.class),
                new PredictionBatch(targetEventRepository, collectionLogRepository,
                        new PredictionGenerator(targetEventRepository, showRepository, predictionRepository,
                                new PredictionProperties(20, 0.95, 1.5), JsonMapper.builder().build())),
                new AccuracyService(targetEventRepository, showRepository),
                new BatchLock(),
                mock(AsyncTaskExecutor.class));

        List<CollectionLog> logs = pipeline.matchAndPredict();
        entityManager.flush();
        entityManager.clear();

        // 지난 이벤트는 실제 셋리스트가 연결되고, 다가오는 이벤트는 예측이 계산된다
        assertThat(targetEventRepository.findById(pastEvent.getId()).orElseThrow().isVerifiable()).isTrue();
        assertThat(predictionRepository.existsByTargetEvent_Id(upcomingEvent.getId())).isTrue();
        assertThat(logs).hasSize(1); // 다가오는 이벤트 1건의 PREDICT 로그
    }
}
