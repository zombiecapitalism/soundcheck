// 듣기/보기 딥링크 — 인증 없이 검색 URL로 자연스럽게 연결한다.
// (Spotify OAuth 플레이리스트 생성은 PRD 2차 — 그 전까지는 링크가 가장 가벼운 다리다)

export interface ListenLink {
  label: string
  url: string
}

export function listenLinks(artistName: string, songName: string): ListenLink[] {
  const query = `${artistName} ${songName}`
  return [
    {
      label: 'YouTube 라이브',
      url: `https://www.youtube.com/results?search_query=${encodeURIComponent(`${query} live`)}`,
    },
    {
      label: 'Spotify',
      url: `https://open.spotify.com/search/${encodeURIComponent(query)}`,
    },
    {
      label: 'YouTube Music',
      url: `https://music.youtube.com/search?q=${encodeURIComponent(query)}`,
    },
  ]
}
