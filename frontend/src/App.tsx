import { Link, Route, Routes } from 'react-router'
import AdminPage from './pages/AdminPage'
import EventListPage from './pages/EventListPage'
import PredictionsPage from './pages/PredictionsPage'
import SongPage from './pages/SongPage'

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="brand">
          Encore
        </Link>
        <span className="brand-sub">셋리스트 예측</span>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<EventListPage />} />
          <Route path="/events/:eventId" element={<PredictionsPage />} />
          <Route path="/events/:eventId/songs/:songKey" element={<SongPage />} />
          <Route path="/admin" element={<AdminPage />} />
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
