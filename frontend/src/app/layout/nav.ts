import { ArrowLeftRight, ChartPie, Coins, Flag, LayoutDashboard, PiggyBank, Settings, Tags, WalletCards, type LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Shown in the mobile bottom bar (at most four; the quick-add button sits in the middle). */
  primary?: boolean
  adminOnly?: boolean
}

export interface NavGroup {
  title?: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    items: [
      { to: '/', label: 'داشبورد', icon: LayoutDashboard, primary: true },
      { to: '/transactions', label: 'تراکنش‌ها', icon: ArrowLeftRight, primary: true },
      { to: '/accounts', label: 'حساب‌ها', icon: WalletCards, primary: true },
    ],
  },
  {
    title: 'برنامه‌ریزی',
    items: [
      { to: '/budgets', label: 'بودجه', icon: PiggyBank },
      { to: '/reports', label: 'گزارش‌ها', icon: ChartPie },
      { to: '/goals', label: 'اهداف', icon: Flag },
    ],
  },
  {
    title: 'دارایی',
    items: [{ to: '/assets', label: 'دارایی‌ها و قیمت‌ها', icon: Coins }],
  },
  {
    title: 'سیستم',
    items: [
      { to: '/categories', label: 'دسته‌بندی‌ها', icon: Tags },
      { to: '/settings', label: 'تنظیمات', icon: Settings },
    ],
  },
]

export const ALL_NAV_ITEMS = NAV_GROUPS.flatMap((g) => g.items)
