import { Link, Route, Routes } from 'react-router'
import AdminPage from './pages/AdminPage'
import EventListPage from './pages/EventListPage'
import PredictionsPage from './pages/PredictionsPage'
import SongPage from './pages/SongPage'
import StatusView from './components/StatusView'

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="brand">
          Soundcheck
        </Link>
        <span className="brand-sub">셋리스트 예측</span>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<EventListPage />} />
          <Route path="/events/:eventId" element={<PredictionsPage />} />
          <Route path="/events/:eventId/songs/:songKey" element={<SongPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <footer className="app-footer">
        setlist data from{' '}
        <a href="https://www.setlist.fm" target="_blank" rel="noreferrer">
          setlist.fm
        </a>
        {' · '}
        <Link to="/admin">관리자</Link>
      </footer>
    </div>
  )
}

/** 미정의 경로 — 빈 화면 대신 안내와 홈 링크(screen-spec §8 개선 항목). */
function NotFound() {
  return (
    <>
      <StatusView kind="empty" message="없는 페이지예요. 주소를 확인해 주세요." />
      <p style={{ textAlign: 'center' }}>
        <Link to="/" className="back-link">
          ← 공연 목록으로
        </Link>
      </p>
    </>
  )
}
