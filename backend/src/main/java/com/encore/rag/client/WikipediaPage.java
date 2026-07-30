package com.encore.rag.client;

/** Wikipedia 문서 — 제목, 평문 본문, 정식 URL(출처 표기용). */
public record WikipediaPage(String title, String extract, String url) {
}
