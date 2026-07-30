package com.encore.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 질의 임베딩 → pgvector 유사도 검색(top-k + 아티스트/곡 메타 필터). */
@Service
public class RagRetriever {

    private final EmbeddingModel embeddingModel;
    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;
    private final RagProperties properties;

    public RagRetriever(EmbeddingModel embeddingModel, RagDocumentRepository documentRepository,
                        RagChunkRepository chunkRepository, RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.properties = properties;
    }

    /** 빈 결과 = 쓸 수 있는 근거가 없다는 뜻이다("정보 없음"으로 응답할 것). */
    public List<RetrievedChunk> retrieve(UUID artistMbid, String songKey, String queryText) {
        // 문서가 아예 없는 아티스트는 임베딩 API를 부를 이유가 없다 — 수집 전에도 우아하게 "정보 없음"
        if (!documentRepository.existsByArtistMbid(artistMbid)) {
            return List.of();
        }
        float[] queryEmbedding = embeddingModel.embed(queryText);
        return chunkRepository.search(artistMbid, songKey, queryEmbedding,
                properties.topK(), properties.minScore());
    }
}
