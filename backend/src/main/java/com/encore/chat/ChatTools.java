package com.encore.chat;

import com.encore.prediction.EvidenceJson;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionCalculator.Evidence;
import com.encore.prediction.PredictionRepository;
import com.encore.rag.RagRetriever;
import com.encore.rag.RetrievedChunk;
import com.encore.rag.SongExplanationService.Source;
import com.encore.setlist.SongKeys;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chat(E8) 도구 — 요청마다 새로 만든다(이벤트·아티스트 컨텍스트 + 사용 출처 수집).
 * 모델은 이 두 도구의 결과 밖 내용을 생성하면 안 된다(ChatPrompts 계약).
 */
public class ChatTools {

    /** 통계 도구를 썼을 때 출처 목록에 넣는 표기 — 문서 출처와 구분된다. */
    static final Source PREDICTION_DATA_SOURCE =
            new Source("Encore", "", "예측 데이터 기준");

    private final RagRetriever retriever;
    private final PredictionRepository predictionRepository;
    private final UUID artistMbid;
    private final Long eventId;
    private final Set<Source> usedSources = new LinkedHashSet<>();

    public ChatTools(RagRetriever retriever, PredictionRepository predictionRepository,
                     UUID artistMbid, Long eventId) {
        this.retriever = retriever;
        this.predictionRepository = predictionRepository;
        this.artistMbid = artistMbid;
        this.eventId = eventId;
    }

    /** 도구 실행 중 실제로 근거가 된 출처 — 스트림 완료 후 응답에 실린다. */
    public List<Source> usedSources() {
        return List.copyOf(usedSources);
    }

    @Tool(description = "아티스트와 곡의 배경 문서(위키 등)를 검색한다. 곡의 의미, 앨범, 밴드 역사 같은 배경 질문에 사용한다.")
    public String searchDocs(@ToolParam(description = "검색할 내용 (영어 권장)") String query) {
        List<RetrievedChunk> chunks = retriever.retrieveAll(artistMbid, query);
        if (chunks.isEmpty()) {
            return "검색 결과 없음 — 이 주제의 문서가 수집되어 있지 않다.";
        }
        StringBuilder sb = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            usedSources.add(new Source(chunk.sourceName(), chunk.sourceUrl(), chunk.documentTitle()));
            sb.append("[").append(chunk.documentTitle()).append(" — ").append(chunk.sourceName())
                    .append("]\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString();
    }

    @Tool(description = "이번 공연의 곡별 연주 확률 예측을 조회한다. 어떤 곡이 나올지, 확률, 예상 셋리스트 질문에 사용한다.")
    public String getPredictionStats(
            @ToolParam(description = "곡명(선택) — 비우면 확률 상위 10곡 요약", required = false) String songName) {
        List<Prediction> predictions =
                predictionRepository.findByTargetEvent_IdOrderByRankAsc(eventId);
        if (predictions.isEmpty()) {
            return "아직 예측이 계산되지 않았다.";
        }
        usedSources.add(PREDICTION_DATA_SOURCE);
        if (songName != null && !songName.isBlank()) {
            String key = SongKeys.normalize(songName);
            return predictions.stream()
                    .filter(p -> p.getSongKey().equals(key) || p.getSongKey().contains(key))
                    .findFirst()
                    .map(ChatTools::describe)
                    .orElse("'" + songName + "'은(는) 예측 목록에 없다 — 최근 표본에서 연주된 적이 없는 곡이다.");
        }
        StringBuilder sb = new StringBuilder("확률 상위 10곡:\n");
        predictions.stream().limit(10).forEach(p -> sb.append(describe(p)).append('\n'));
        sb.append("(전체 예측 곡 수: ").append(predictions.size()).append(")");
        return sb.toString();
    }

    private static String describe(Prediction p) {
        Evidence evidence = EvidenceJson.parse(p.getEvidence());
        StringBuilder sb = new StringBuilder();
        sb.append(p.getRank()).append(". ").append(p.getSongName())
                .append(" — 확률 ").append(p.getProbability().movePointRight(2).intValue()).append("%")
                .append(", 최근 ").append(p.getSampleSize()).append("회 중 ")
                .append(p.getPlayedCount()).append("회 연주");
        if (p.getAvgPosition() != null) {
            sb.append(", 평균 ").append(p.getAvgPosition()).append("번째");
        }
        if (p.getEncoreRatio() != null && p.getEncoreRatio().doubleValue() >= 0.5) {
            sb.append(", 앙코르 단골");
        }
        if (evidence != null && evidence.trend() != null) {
            switch (evidence.trend()) {
                case RISING -> sb.append(", 최근 상승세");
                case FALLING -> sb.append(", 최근 하락세");
                case STABLE -> { }
            }
        }
        return sb.toString();
    }
}
