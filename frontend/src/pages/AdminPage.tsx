import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import {
  adminApi,
  clearAdminAuth,
  hasAdminAuth,
  saveAdminAuth,
  type ArtistCandidate,
} from '../api/admin'
import type { ShowType } from '../api/types'

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

  // 401이면 세션이 끊긴 것 — 로그인 게이트로 되돌린다
  if (logsQuery.error instanceof ApiError && logsQuery.error.status === 401) {
    clearAdminAuth()
    onAuthExpired()
  }

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
      <BatchSection collecting={logsQuery.data?.collecting ?? false} onDone={invalidate} />
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

function BatchSection({ collecting, onDone }: { collecting: boolean; onDone: () => void }) {
  const collect = useMutation({ mutationFn: adminApi.startCollect, onSettled: onDone })
  const predict = useMutation({ mutationFn: adminApi.runPredict, onSettled: onDone })

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
      </div>
      {collect.error && <p className="form-error">{collect.error.message}</p>}
      {predict.isSuccess && <p className="form-hint">예측 완료 — 이벤트 {predict.data.length}건 처리</p>}
      {predict.error && <p className="form-error">{predict.error.message}</p>}
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
          <input type="date" value={eventDate} onChange={(e) => setEventDate(e.target.value)} required />
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
