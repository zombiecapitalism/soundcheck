// 예습 체크리스트 — 순수 로직. 저장은 훅(usePracticeChecklist)이 localStorage로 한다.

/** 체크 토글 — 있으면 빼고 없으면 넣는다. 원본을 바꾸지 않는다. */
export function toggleKey(keys: readonly string[], key: string): string[] {
  return keys.includes(key) ? keys.filter((k) => k !== key) : [...keys, key]
}

/**
 * 예습 진행률 — 예상 셋 규모(확률 상위 setSize곡) 기준으로 센다.
 * 보기(확률순/예상 순서)를 바꿔도 분모가 흔들리지 않게 rank 상위 N곡으로 고정.
 */
export function practiceProgress(
  predictionsByRank: readonly { songKey: string }[],
  checkedKeys: ReadonlySet<string>,
  setSize: number,
): { done: number; total: number } {
  const target = predictionsByRank.slice(0, Math.max(0, setSize))
  return {
    done: target.filter((p) => checkedKeys.has(p.songKey)).length,
    total: target.length,
  }
}
