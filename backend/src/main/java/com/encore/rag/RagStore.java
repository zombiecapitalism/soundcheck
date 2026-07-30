package com.encore.rag;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 문서 + 청크를 한 트랜잭션으로 저장한다. 수집기(RagIngester)는 HTTP·임베딩 호출이 섞여
 * 트랜잭션을 걸 수 없으므로, 쓰기만 여기로 분리한다 — 청크 절반만 저장된 문서를 남기지 않는다.
 */
@Component
public class RagStore {

    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;

    public RagStore(RagDocumentRepository documentRepository, RagChunkRepository chunkRepository) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /** chunks와 embeddings는 같은 길이·같은 순서를 전제한다. */
    @Transactional
    public void save(RagDocument document, List<RagChunker.Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "청크 수와 임베딩 수가 다릅니다: " + chunks.size() + " != " + embeddings.size());
        }
        RagDocument saved = documentRepository.save(document);
        for (int i = 0; i < chunks.size(); i++) {
            RagChunker.Chunk chunk = chunks.get(i);
            chunkRepository.insert(saved.getId(), i, chunk.content(), embeddings.get(i), chunk.tokenCount());
        }
    }
}
