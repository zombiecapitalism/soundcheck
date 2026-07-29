import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App.tsx'
import { ApiError } from './api/client'

// 예측은 배치가 하루 한 번 갱신하는 데이터라 공격적으로 다시 가져올 이유가 없다
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      // 404 같은 4xx는 다시 요청해도 결과가 같다 — 재시도는 5xx/네트워크 오류만
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
