import type { ThemePreference } from '@/lib/api/types'

export function applyTheme(theme: ThemePreference) {
  const dark = theme === 'DARK' || (theme === 'SYSTEM' && window.matchMedia?.('(prefers-color-scheme: dark)').matches)
  document.documentElement.classList.toggle('dark', !!dark)
}
