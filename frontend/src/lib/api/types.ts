import type { DigitStyle } from '../format/number'
import type { DisplayUnit } from '../format/money'

export type Role = 'USER' | 'ADMIN'
export type ThemePreference = 'SYSTEM' | 'LIGHT' | 'DARK'

export interface Settings {
  displayUnit: DisplayUnit
  digitStyle: DigitStyle
  theme: ThemePreference
  wealthUnits: string[]
  inflationRate: string | null
  aiEnabled: boolean
  aiShareDescriptions: boolean
}

export interface Me {
  id: number
  username: string
  displayName: string
  role: Role
  totpEnabled: boolean
  settings: Settings
}

export interface AuthStatus {
  authenticated: boolean
  registrationOpen: boolean
  hasUsers: boolean
  user: Me | null
}

export interface AdminUser {
  id: number
  username: string
  displayName: string
  role: Role
  enabled: boolean
  totpEnabled: boolean
  createdAt: string
  lastLoginAt: string | null
}
