import { LayoutDashboard, Settings, type LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Shown in the mobile bottom bar. */
  primary?: boolean
  adminOnly?: boolean
}

export interface NavGroup {
  title?: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    items: [{ to: '/', label: 'داشبورد', icon: LayoutDashboard, primary: true }],
  },
  {
    title: 'سیستم',
    items: [{ to: '/settings', label: 'تنظیمات', icon: Settings }],
  },
]

export const ALL_NAV_ITEMS = NAV_GROUPS.flatMap((g) => g.items)
