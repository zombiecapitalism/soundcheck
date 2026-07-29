/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 중 백엔드(8080)로 프록시 — CORS 설정 없이 같은 오리진처럼 동작한다
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node', // 순수 함수(포맷 유틸)만 테스트하므로 jsdom 불필요
  },
})
