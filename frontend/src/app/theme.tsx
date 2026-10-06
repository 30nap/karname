import { useEffect } from 'react'
import { useAuthStatus } from '@/features/auth/api'
import type { ThemePreference } from '@/lib/api/types'
import { applyTheme } from './applyTheme'

const STORAGE_KEY = 'karname.theme'

function readStoredTheme(): ThemePreference {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'LIGHT' || value === 'DARK' ? value : 'SYSTEM'
  } catch {
    return 'SYSTEM'
  }
}

/** Keeps the <html> "dark" class in sync with the user's setting (or the stored/system theme before login). */
export function ThemeSync() {
  const { data } = useAuthStatus()
  const theme = data?.user?.settings.theme ?? readStoredTheme()

  useEffect(() => {
    applyTheme(theme)
    try {
      localStorage.setItem(STORAGE_KEY, theme)
    } catch {
      // storage unavailable (private mode); theme still applies for this page
    }
    if (theme !== 'SYSTEM') return
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    const listener = () => applyTheme('SYSTEM')
    media?.addEventListener('change', listener)
    return () => media?.removeEventListener('change', listener)
  }, [theme])

  return null
}
