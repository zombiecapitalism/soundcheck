package com.encore.rag;

import com.encore.batch.BatchLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * RAG 수집 배치의 백그라운드 실행 + 중복 실행 방지.
 * 수집 파이프라인(CollectionPipeline)과 같은 방식 — @Async 자기호출 프록시 문제를 피해
 * 실행기에 직접 제출하고, 제출 실패 시 락을 즉시 되돌린다.
 */
@Service
public class RagIngestRunner {

    private final RagIngester ingester;
    private final BatchLock batchLock;
    private final AsyncTaskExecutor taskExecutor;

    public RagIngestRunner(RagIngester ingester, BatchLock batchLock,
                           @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.ingester = ingester;
        this.batchLock = batchLock;
        this.taskExecutor = taskExecutor;
    }

    /** 시작했으면 true, 이미 실행 중이면 false. 결과는 collection_log(EMBED)로 남는다. */
    public boolean tryStart() {
        if (!batchLock.tryAcquireRagIngest()) {
            return false;
        }
        try {
            taskExecutor.submit(() -> {
                try {
                    ingester.ingestAll();
                } finally {
                    batchLock.releaseRagIngest();
                }
            });
        } catch (RuntimeException e) {
            batchLock.releaseRagIngest();
            throw e;
        }
        return true;
    }

    public boolean isRunning() {
        return batchLock.isRagIngesting();
    }
}
