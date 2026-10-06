import {
  ArrowLeftRight, Banknote, Bitcoin, Building2, Car, CircleDollarSign, Coins, CreditCard, HandCoins, Landmark, LineChart, Percent, Scale3d, Tag, Wallet, type LucideIcon,
} from 'lucide-react'
import type { AccountType } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { CATEGORY_ICONS } from './category-icons'

export function CategoryIcon({ name, className }: { name: string | null | undefined; className?: string }) {
  const Icon = (name && CATEGORY_ICONS[name]) || Tag
  return <Icon className={cn('size-4', className)} aria-hidden />
}

const ACCOUNT_ICONS: Record<AccountType, LucideIcon> = {
  CASH: Banknote, BANK: Landmark, EWALLET: Wallet, CURRENCY: CircleDollarSign, GOLD: Coins, CRYPTO: Bitcoin,
  INVESTMENT: LineChart, PROPERTY: Building2, VEHICLE: Car, RECEIVABLE: HandCoins, OTHER_ASSET: Scale3d,
  LOAN: Percent, DEBT: HandCoins, CREDIT: CreditCard,
}

export function AccountIcon({ type, className }: { type: AccountType; className?: string }) {
  const Icon = ACCOUNT_ICONS[type] ?? Wallet
  return <Icon className={cn('size-4', className)} aria-hidden />
}

export function TransferIcon({ className }: { className?: string }) {
  return <ArrowLeftRight className={cn('size-4', className)} aria-hidden />
}
