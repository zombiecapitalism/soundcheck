interface StatusViewProps {
  kind: 'loading' | 'error' | 'empty'
  message: string
  onRetry?: () => void
}

/** 로딩/에러/빈 상태 공용 화면. 공연장 네트워크는 느리다 — 재시도 버튼을 크게 둔다. */
export default function StatusView({ kind, message, onRetry }: StatusViewProps) {
  return (
    <div className={`status-view status-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {kind === 'loading' && <div className="spinner" aria-hidden="true" />}
      <p>{message}</p>
      {kind === 'error' && onRetry && (
        <button type="button" className="retry-button" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  )
}
