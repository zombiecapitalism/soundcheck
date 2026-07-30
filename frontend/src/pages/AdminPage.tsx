import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import {
  adminApi,
  clearAdminAuth,
  hasAdminAuth,
  saveAdminAuth,
  type ArtistCandidate,
  type KoreaShow,
} from '../api/admin'
import type { ShowType } from '../api/types'
import { formatEventDate } from '../lib/format'

/**
 * 관리자 콘솔 — 내한 소식을 들은 관리자가 SQL 없이 아티스트·이벤트를 등록하고
 * 배치를 트리거한다. 서버 상태는 Query, 폼 입력 같은 로컬 상태만 useState.
 */
export default function AdminPage() {
  const [authed, setAuthed] = useState(hasAdminAuth)

  if (!authed) {
    return <AdminLogin onSuccess={() => setAuthed(true)} />
  }
  return <AdminConsole onAuthExpired={() => setAuthed(false)} />
}

function AdminLogin({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    saveAdminAuth(username, password)
    try {
      await adminApi.logs() // 자격증명 검증을 겸한다
      onSuccess()
    } catch (e) {
      clearAdminAuth()
      setError(e instanceof Error ? e.message : '로그인에 실패했어요.')
    }
  }

  return (
    <form className="admin-login" onSubmit={submit}>
      <h1 className="page-title">관리자 로그인</h1>
      <label>
        아이디
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
      </label>
      <label>
        비밀번호
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      {error && <p className="form-error">{error}</p>}
      <button type="submit" className="primary-button">
        로그인
      </button>
    </form>
  )
}

function AdminConsole({ onAuthExpired }: { onAuthExpired: () => void }) {
  const queryClient = useQueryClient()

  const logsQuery = useQuery({
    queryKey: ['admin', 'logs'],
    queryFn: adminApi.logs,
    // 수집이 도는 동안은 자주 갱신해서 진행 상황을 보여준다
    refetchInterval: (query) => (query.state.data?.collecting ? 3000 : 15000),
  })

  // 401이면 세션이 끊긴 것 — 로그인 게이트로 되돌린다.
  // 렌더 중 부모 setState는 React 규칙 위반이므로 반드시 effect에서 처리한다.
  const authExpired = logsQuery.error instanceof ApiError && logsQuery.error.status === 401
  useEffect(() => {
    if (authExpired) {
      clearAdminAuth()
      onAuthExpired()
    }
  }, [authExpired, onAuthExpired])

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['admin'] })
    queryClient.invalidateQueries({ queryKey: ['events'] })
  }

  return (
    <div className="admin-console">
      <div className="admin-header">
        <h1 className="page-title">관리자 콘솔</h1>
        <button
          type="button"
          className="text-button"
          onClick={() => {
            clearAdminAuth()
            onAuthExpired()
          }}
        >
          로그아웃
        </button>
      </div>
      <BatchSection
        collecting={logsQuery.data?.collecting ?? false}
        ragIngesting={logsQuery.data?.ragIngesting ?? false}
        onDone={invalidate}
      />
      <AiDashboardSection />
      <RagSection />
      <KoreaShowSection onRegistered={invalidate} />
      <ArtistSection onRegistered={invalidate} />
      <EventSection onCreated={invalidate} />
      <LogsSection
        logs={logsQuery.data?.logs ?? []}
        loading={logsQuery.isPending}
        collecting={logsQuery.data?.collecting ?? false}
      />
    </div>
  )
}

function BatchSection({
  collecting,
  ragIngesting,
  onDone,
}: {
  collecting: boolean
  ragIngesting: boolean
  onDone: () => void
}) {
  const collect = useMutation({ mutationFn: adminApi.startCollect, onSettled: onDone })
  const predict = useMutation({ mutationFn: adminApi.runPredict, onSettled: onDone })
  const ragIngest = useMutation({ mutationFn: adminApi.startRagIngest, onSettled: onDone })

  return (
    <section className="admin-section">
      <h2>배치 실행</h2>
      <div className="admin-actions">
        <button
          type="button"
          className="primary-button"
          disabled={collecting || collect.isPending}
          onClick={() => collect.mutate()}
        >
          {collecting ? '수집 진행 중…' : '셋리스트 수집'}
        </button>
        <button
          type="button"
          className="primary-button"
          disabled={predict.isPending}
          onClick={() => predict.mutate()}
        >
          {predict.isPending ? '예측 계산 중…' : '예측 재계산'}
        </button>
        <button
          type="button"
          className="primary-button"
          disabled={ragIngesting || ragIngest.isPending}
          onClick={() => ragIngest.mutate()}
        >
          {ragIngesting ? 'RAG 수집 진행 중…' : 'RAG 문서 수집'}
        </button>
      </div>
      {collect.error && <p className="form-error">{collect.error.message}</p>}
      {predict.isSuccess && <p className="form-hint">예측 완료 — 이벤트 {predict.data.length}건 처리</p>}
      {predict.error && <p className="form-error">{predict.error.message}</p>}
      {ragIngest.isSuccess && (
        <p className="form-hint">RAG 수집 시작 — 결과는 아래 이력(EMBED)에서 확인</p>
      )}
      {ragIngest.error && <p className="form-error">{ragIngest.error.message}</p>}
    </section>
  )
}

/** AI 사용량(E9) — 오늘(KST) 호출·캐시·토큰·예상 비용. 숫자 카드 위주, 차트 없음. */
function AiDashboardSection() {
  const dashboard = useQuery({
    queryKey: ['admin', 'ai-dashboard'],
    queryFn: adminApi.aiDashboard,
    refetchInterval: 30000,
  })
  const data = dashboard.data

  return (
    <section className="admin-section">
      <h2>AI 사용량 (오늘)</h2>
      {dashboard.isPending && <p className="form-hint">불러오는 중…</p>}
      {dashboard.error && <p className="form-error">{dashboard.error.message}</p>}
      {data && (
        <>
          <div className="stat-cards">
            <div className="stat-card">
              <span className="stat-value">{data.totalCalls}</span>
              <span className="stat-label">호출</span>
            </div>
            <div className="stat-card">
              <span className="stat-value">{Math.round(data.cacheHitRate * 100)}%</span>
              <span className="stat-label">캐시 히트</span>
            </div>
            <div className="stat-card">
              <span className="stat-value">
                {(data.inputTokens + data.outputTokens + data.embeddingTokens).toLocaleString()}
              </span>
              <span className="stat-label">토큰</span>
            </div>
            <div className="stat-card">
              <span className="stat-value">${data.estimatedCostUsd.toFixed(4)}</span>
              <span className="stat-label">예상 비용</span>
            </div>
          </div>
          {data.byType.length > 0 && (
            <div className="log-table-wrap">
              <table className="log-table">
                <thead>
                  <tr>
                    <th>용도</th>
                    <th>호출</th>
                    <th>평균 지연</th>
                    <th>토큰(입/출)</th>
                    <th>캐시</th>
                    <th>오류</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byType.map((row) => (
                    <tr key={row.callType}>
                      <td>{row.callType}</td>
                      <td>{row.calls}</td>
                      <td>{row.avgLatencyMs != null ? `${row.avgLatencyMs}ms` : '—'}</td>
                      <td>
                        {row.inputTokens.toLocaleString()}/{row.outputTokens.toLocaleString()}
                      </td>
                      <td>{row.cacheHits}</td>
                      <td>{row.errors}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.totalCalls === 0 && <p className="form-hint">오늘은 아직 LLM 호출이 없어요.</p>}
        </>
      )}
    </section>
  )
}

/** RAG 저장소(E10) — 아티스트별 임베딩·캐시 상태, 문서 목록·삭제, 캐시 무효화. */
function RagSection() {
  const queryClient = useQueryClient()
  const [selectedMbid, setSelectedMbid] = useState<string | null>(null)

  const status = useQuery({ queryKey: ['admin', 'rag-status'], queryFn: adminApi.ragStatus })
  const documents = useQuery({
    queryKey: ['admin', 'rag-documents', selectedMbid],
    queryFn: () => adminApi.ragDocuments(selectedMbid!),
    enabled: selectedMbid != null,
  })
  const invalidateRag = () => {
    queryClient.invalidateQueries({ queryKey: ['admin', 'rag-status'] })
    queryClient.invalidateQueries({ queryKey: ['admin', 'rag-documents'] })
  }
  const evict = useMutation({ mutationFn: adminApi.evictExplanationCache, onSuccess: invalidateRag })
  const deleteDoc = useMutation({ mutationFn: adminApi.deleteRagDocument, onSuccess: invalidateRag })

  return (
    <section className="admin-section">
      <h2>RAG 저장소</h2>
      {status.isPending && <p className="form-hint">불러오는 중…</p>}
      {status.error && <p className="form-error">{status.error.message}</p>}
      {status.data && status.data.length === 0 && (
        <p className="form-hint">수집 대상 아티스트가 없어요.</p>
      )}
      {status.data && status.data.length > 0 && (
        <div className="log-table-wrap">
          <table className="log-table">
            <thead>
              <tr>
                <th>아티스트</th>
                <th>문서/청크</th>
                <th>캐시된 설명</th>
                <th>마지막 임베딩</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {status.data.map((row) => (
                <tr key={row.artistMbid}>
                  <td>
                    <button
                      type="button"
                      className="text-button"
                      onClick={() =>
                        setSelectedMbid(selectedMbid === row.artistMbid ? null : row.artistMbid)
                      }
                    >
                      {row.artistName}
                    </button>
                  </td>
                  <td>
                    {row.documentCount}/{row.chunkCount}
                  </td>
                  <td>{row.explanationCount}</td>
                  <td>
                    {row.lastEmbedAt
                      ? `${new Date(row.lastEmbedAt).toLocaleString('ko-KR')} (${row.lastEmbedStatus})`
                      : '—'}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="text-button"
                      disabled={evict.isPending || row.explanationCount === 0}
                      onClick={() => evict.mutate(row.artistMbid)}
                    >
                      캐시 비우기
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selectedMbid && documents.data && (
        <>
          <h3 className="rag-doc-title">문서 목록</h3>
          {documents.data.length === 0 && <p className="form-hint">수집된 문서가 없어요.</p>}
          {documents.data.length > 0 && (
            <ul className="candidate-list">
              {documents.data.map((doc) => (
                <li key={doc.id} className="candidate-item">
                  <div className="candidate-info">
                    <strong>{doc.title}</strong>
                    <span className="candidate-hint">
                      {' — '}
                      {doc.docType}
                      {doc.songKey && ` · ${doc.songKey}`} · 청크 {doc.chunkCount} ·{' '}
                      <a href={doc.sourceUrl} target="_blank" rel="noreferrer">
                        출처 ↗
                      </a>
                    </span>
                  </div>
                  <button
                    type="button"
                    className="text-button danger"
                    disabled={deleteDoc.isPending}
                    onClick={() => deleteDoc.mutate(doc.id)}
                  >
                    삭제
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {evict.error && <p className="form-error">{evict.error.message}</p>}
      {deleteDoc.error && <p className="form-error">{deleteDoc.error.message}</p>}
    </section>
  )
}

/**
 * 내한 자동 감지 — 별도 크롤링 없이 수집 데이터의 KR 미래 공연을 보여준다.
 * setlist.fm은 공연 발표 시 곡 없는 페이지가 먼저 생기므로 수집만 돌면 잡힌다.
 */
function KoreaShowSection({ onRegistered }: { onRegistered: () => void }) {
  const shows = useQuery({ queryKey: ['admin', 'korea-shows'], queryFn: adminApi.koreaShows })
  const register = useMutation({
    mutationFn: (show: KoreaShow) =>
      adminApi.createEvent({
        artistMbid: show.artistMbid,
        // 공연장명을 이벤트명으로 쓰면 메인 카드에서 장소와 중복돼 어색하다(실사용 확인)
        eventName: `${show.artistName} 내한 공연`,
        eventDate: show.eventDate,
        venueName: show.venueName,
        // 수집 시 페스티벌로 판정된 공연은 페스티벌 셋으로 예측한다
        expectedShowType: show.showType === 'FESTIVAL' ? 'FESTIVAL' : 'SOLO',
      }),
    onSuccess: onRegistered,
  })

  return (
    <section className="admin-section">
      <h2>내한 감지</h2>
      {shows.isPending && <p className="form-hint">확인 중…</p>}
      {shows.data && shows.data.length === 0 && (
        <p className="form-hint">
          감지된 내한 일정이 없어요. 수집 대상 아티스트의 한국 공연이 setlist.fm에
          등록되면 다음 수집 때 여기에 표시됩니다.
        </p>
      )}
      {shows.data && shows.data.length > 0 && (
        <ul className="candidate-list">
          {shows.data.map((show) => (
            <li key={show.setlistId} className="candidate-item">
              <div className="candidate-info">
                <strong>{show.artistName}</strong>
                <span className="candidate-hint">
                  {' — '}
                  {formatEventDate(show.eventDate)}
                  {show.venueName && ` · ${show.venueName}`}
                  {show.cityName && ` (${show.cityName})`}
                  {show.showType === 'FESTIVAL' && ' · 페스티벌'}
                </span>
              </div>
              {show.alreadyRegistered ? (
                <span className="candidate-registered">등록됨</span>
              ) : (
                <button
                  type="button"
                  className="primary-button small"
                  disabled={register.isPending}
                  onClick={() => register.mutate(show)}
                >
                  이벤트로 등록
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {register.isSuccess && <p className="form-hint">등록 완료 — 예측까지 계산됐어요.</p>}
      {register.error && <p className="form-error">{register.error.message}</p>}
      {shows.error && <p className="form-error">{shows.error.message}</p>}
    </section>
  )
}

function ArtistSection({ onRegistered }: { onRegistered: () => void }) {
  const [name, setName] = useState('')
  const [searched, setSearched] = useState('')

  const search = useQuery({
    queryKey: ['admin', 'artist-search', searched],
    queryFn: () => adminApi.searchArtists(searched),
    enabled: searched.length > 0,
  })
  const register = useMutation({
    mutationFn: (candidate: ArtistCandidate) => adminApi.registerArtist(candidate),
    onSuccess: async () => {
      onRegistered()
      // 등록 직후 바로 수집을 걸어준다 — 이미 실행 중이면(409) 무시하고 다음 배치에 맡긴다
      try {
        await adminApi.startCollect()
      } catch {
        /* 409 등은 대시보드에서 확인 */
      }
    },
  })

  return (
    <section className="admin-section">
      <h2>아티스트 등록</h2>
      <form
        className="admin-inline-form"
        onSubmit={(e) => {
          e.preventDefault()
          setSearched(name.trim())
        }}
      >
        <input
          placeholder="밴드명으로 setlist.fm 검색"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button type="submit" className="primary-button" disabled={!name.trim() || search.isFetching}>
          검색
        </button>
      </form>
      {search.isFetching && <p className="form-hint">검색 중…</p>}
      {search.error && <p className="form-error">{search.error.message}</p>}
      {search.data && search.data.length === 0 && <p className="form-hint">검색 결과가 없어요.</p>}
      {search.data && search.data.length > 0 && (
        <ul className="candidate-list">
          {search.data.map((candidate) => (
            <li key={candidate.mbid} className="candidate-item">
              <div className="candidate-info">
                <strong>{candidate.name}</strong>
                {candidate.disambiguation && (
                  <span className="candidate-hint"> — {candidate.disambiguation}</span>
                )}
              </div>
              {candidate.alreadyRegistered ? (
                <span className="candidate-registered">등록됨</span>
              ) : (
                <button
                  type="button"
                  className="primary-button small"
                  disabled={register.isPending}
                  onClick={() => register.mutate(candidate)}
                >
                  등록
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {register.isSuccess && (
        <p className="form-hint">등록 완료 — 셋리스트 수집을 시작했어요. 아래 이력에서 진행을 확인하세요.</p>
      )}
      {register.error && <p className="form-error">{register.error.message}</p>}
    </section>
  )
}

function EventSection({ onCreated }: { onCreated: () => void }) {
  const artists = useQuery({ queryKey: ['admin', 'artists'], queryFn: adminApi.registeredArtists })
  const [artistMbid, setArtistMbid] = useState('')
  const [eventName, setEventName] = useState('')
  const [eventDate, setEventDate] = useState('')
  const [venueName, setVenueName] = useState('')
  const [showType, setShowType] = useState<ShowType>('FESTIVAL')

  const create = useMutation({
    mutationFn: adminApi.createEvent,
    onSuccess: onCreated,
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate({
      artistMbid,
      eventName: eventName.trim(),
      eventDate,
      venueName: venueName.trim() || null,
      expectedShowType: showType,
    })
  }

  return (
    <section className="admin-section">
      <h2>이벤트 등록</h2>
      <form className="admin-form" onSubmit={submit}>
        <label>
          아티스트
          <select value={artistMbid} onChange={(e) => setArtistMbid(e.target.value)} required>
            <option value="" disabled>
              선택하세요
            </option>
            {artists.data?.map((artist) => (
              <option key={artist.mbid} value={artist.mbid}>
                {artist.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          이벤트명
          <input
            value={eventName}
            onChange={(e) => setEventName(e.target.value)}
            placeholder="2026 부산국제록페스티벌"
            required
          />
        </label>
        <label>
          공연일
          {/* 서버가 과거 날짜 등록을 400으로 거부한다 — 입력 단계에서 미리 막는다.
              toISOString()은 UTC라 자정~오전 9시(KST)에 하루 어긋난다 — 로컬 날짜로 만든다 */}
          <input
            type="date"
            value={eventDate}
            min={new Date().toLocaleDateString('sv-SE')}
            onChange={(e) => setEventDate(e.target.value)}
            required
          />
        </label>
        <label>
          공연장 (선택)
          <input value={venueName} onChange={(e) => setVenueName(e.target.value)} placeholder="삼락생태공원" />
        </label>
        <label>
          공연 유형
          <select value={showType} onChange={(e) => setShowType(e.target.value as ShowType)}>
            <option value="FESTIVAL">FESTIVAL — 페스티벌 셋</option>
            <option value="SOLO">SOLO — 단독 공연</option>
          </select>
        </label>
        <button type="submit" className="primary-button" disabled={create.isPending}>
          {create.isPending ? '등록 중…' : '이벤트 등록 + 예측 계산'}
        </button>
      </form>
      {create.isSuccess && (
        <p className="form-hint">
          등록 완료 (예측: {create.data.predictionStatus === 'SUCCESS' ? '계산됨' : create.data.predictionStatus}
          {create.data.predictionStatus === 'FAILED' && ' — 수집 완료 후 예측 재계산을 눌러주세요'})
        </p>
      )}
      {create.error && <p className="form-error">{create.error.message}</p>}
    </section>
  )
}

function LogsSection({
  logs,
  loading,
  collecting,
}: {
  logs: import('../api/admin').BatchLogEntry[]
  loading: boolean
  collecting: boolean
}) {
  return (
    <section className="admin-section">
      <h2>
        배치 이력 {collecting && <span className="collecting-badge">수집 진행 중</span>}
      </h2>
      {loading && <p className="form-hint">불러오는 중…</p>}
      {!loading && logs.length === 0 && <p className="form-hint">아직 실행 이력이 없어요.</p>}
      {logs.length > 0 && (
        <div className="log-table-wrap">
          <table className="log-table">
            <thead>
              <tr>
                <th>작업</th>
                <th>상태</th>
                <th>수집/갱신/스킵</th>
                <th>시작</th>
                <th>오류</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((log) => (
                <tr key={log.id}>
                  <td>{log.jobType}</td>
                  <td>
                    <span className={`log-status log-${log.status.toLowerCase()}`}>{log.status}</span>
                  </td>
                  <td>
                    {log.fetched}/{log.updated}/{log.skipped}
                  </td>
                  <td>{new Date(log.startedAt).toLocaleString('ko-KR')}</td>
                  <td className="log-error">{log.errorMessage ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
