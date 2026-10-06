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

export type CommodityKind = 'TOMAN' | 'FIAT' | 'GOLD' | 'COIN' | 'CRYPTO' | 'SECURITY' | 'PROPERTY' | 'VEHICLE' | 'OTHER'

export interface LatestPrice {
  priceToman: string
  pricedAt: string
  source: string
  personal: boolean
  stale: boolean
}

export interface Commodity {
  code: string
  nameFa: string
  unitFa: string
  kind: CommodityKind
  scale: number
  custom: boolean
  latestPrice: LatestPrice | null
}

export interface PriceRecord {
  id: number
  commodity: string
  priceToman: string
  pricedAt: string
  source: string
  personal: boolean
}

export type AccountType =
  | 'CASH' | 'BANK' | 'EWALLET' | 'CURRENCY' | 'GOLD' | 'CRYPTO' | 'INVESTMENT'
  | 'PROPERTY' | 'VEHICLE' | 'RECEIVABLE' | 'OTHER_ASSET' | 'LOAN' | 'DEBT' | 'CREDIT'

export interface Account {
  id: number
  name: string
  type: AccountType
  liability: boolean
  commodity: string
  bank: string | null
  identifierHints: string[]
  counterparty: string | null
  icon: string | null
  includeInNetWorth: boolean
  archived: boolean
  notes: string | null
  sortOrder: number
  balance: string
  valueToman: string | null
  priced: boolean
  priceStale: boolean
  createdAt: string
}

export interface AccountInput {
  name: string
  type: AccountType
  commodity: string
  bank?: string | null
  identifierHints?: string[]
  counterparty?: string | null
  icon?: string | null
  includeInNetWorth?: boolean
  notes?: string | null
  openingBalance?: string | null
  openingDate?: string | null
}

export type CategoryKind = 'INCOME' | 'EXPENSE'

export interface Category {
  id: number
  parentId: number | null
  kind: CategoryKind
  name: string
  icon: string | null
  systemKey: string | null
  archived: boolean
  sortOrder: number
}

export type TransactionType = 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'OPENING' | 'ADJUSTMENT'
export type TransactionSource = 'MANUAL' | 'AI' | 'SMS' | 'RECURRING' | 'IMPORT' | 'LOAN' | 'CHEQUE' | 'SYSTEM'

export interface AccountRef {
  id: number
  name: string
  commodity: string
  type: AccountType
}

export interface CategoryRef {
  id: number
  name: string
  icon: string | null
  kind: CategoryKind
  parentId: number | null
  parentName: string | null
}

export interface Transaction {
  id: number
  type: TransactionType
  date: string
  account: AccountRef
  amount: string
  toAccount: AccountRef | null
  toAmount: string | null
  fee: string | null
  category: CategoryRef | null
  description: string | null
  notes: string | null
  tags: string[]
  source: TransactionSource
  createdAt: string
}

export interface TransactionInput {
  type: TransactionType
  date: string
  accountId: number
  amount: string
  toAccountId?: number | null
  toAmount?: string | null
  fee?: string | null
  categoryId?: number | null
  description?: string | null
  notes?: string | null
  tags?: string[]
}

export interface TransactionPage {
  items: Transaction[]
  page: number
  size: number
  total: number
  incomeToman: string
  expenseToman: string
  unpricedCount: number
}

export interface TransactionFilter {
  from?: string
  to?: string
  type?: TransactionType[]
  accountId?: number
  categoryId?: number
  uncategorized?: boolean
  q?: string
}

export type AssetClass = 'TOMAN' | 'FIAT' | 'CRYPTO' | 'GOLD' | 'SECURITY' | 'PROPERTY' | 'OTHER'

export interface NetWorth {
  asOf: string
  totalToman: string
  assetsToman: string
  liabilitiesToman: string
  alternatives: { code: string; nameFa: string; unitFa: string; value: string; stale: boolean }[]
  allocation: { assetClass: AssetClass; valueToman: string; share: string }[]
  unpriced: { accountId: number; name: string; commodity: string; balance: string }[]
}

export interface NetWorthPoint {
  month: string
  date: string
  totalToman: string
  assetsToman: string
  liabilitiesToman: string
  alternatives: Record<string, string>
  unpricedAccounts: number
}

export interface MonthSummary {
  month: string
  incomeToman: string
  expenseToman: string
  netToman: string
  savingsRate: string | null
  unpricedCount: number
}

export interface Dashboard {
  netWorth: NetWorth
  currentMonth: MonthSummary
  previousMonth: MonthSummary
  recentTransactions: Transaction[]
}

export interface CostBasis {
  accountId: number
  commodity: string
  quantity: string
  averageCostToman: string | null
  costBasisToman: string
  marketValueToman: string | null
  unrealizedToman: string | null
  realizedToman: string
  costComplete: boolean
}

export type BudgetStatus = 'OK' | 'WARNING' | 'OVER'

export interface BudgetItem {
  categoryId: number
  name: string
  icon: string | null
  parentId: number | null
  parentName: string | null
  amount: string
  spent: string
  remaining: string
  ratio: string
  status: BudgetStatus
  recurring: boolean
  since: string
  projected: string | null
  unpricedCount: number
}

export interface BudgetMonth {
  month: string
  daysInMonth: number
  daysElapsed: number
  current: boolean
  totalBudget: string
  totalSpent: string
  unbudgetedSpent: string
  totalExpense: string
  items: BudgetItem[]
}

export interface BudgetSuggestion {
  categoryId: number
  name: string
  icon: string | null
  averageToman: string
  suggestedToman: string
  currentBudget: string | null
}

export interface MonthTotals {
  month: string
  incomeToman: string
  expenseToman: string
  netToman: string
  savingsRate: string | null
  unpricedCount: number
  partial: boolean
}

export type ReportKind = 'INCOME' | 'EXPENSE'

export interface CategoryLine {
  categoryId: number | null
  name: string | null
  icon: string | null
  valueToman: string
  share: string
  count: number
  previousToman: string
  averageToman: string | null
  children: CategoryLine[]
}

export interface CategoryReport {
  fromMonth: string
  toMonth: string
  kind: ReportKind
  totalToman: string
  previousTotalToman: string
  unpricedCount: number
  items: CategoryLine[]
}

export interface TopItem {
  transaction: Transaction
  valueToman: string
}

export interface Anomaly {
  categoryId: number
  name: string
  icon: string | null
  currentToman: string
  averageToman: string
  ratio: string | null
  zScore: string | null
  historyMonths: number
}

export interface Goal {
  id: number
  name: string
  icon: string | null
  targetAmount: string
  commodity: string
  targetDate: string | null
  accountIds: number[]
  manualAmount: string | null
  notes: string | null
  archived: boolean
  currentAmount: string | null
  currentToman: string | null
  progress: string | null
  remaining: string | null
  achieved: boolean
  monthlyChange: string | null
  etaMonth: string | null
  monthsToGoal: number | null
  monthsLeft: number | null
  requiredPerMonth: string | null
  requiredPerMonthToman: string | null
  onTrack: boolean | null
  missingPrices: boolean
}

export interface GoalInput {
  name: string
  icon?: string | null
  targetAmount: string
  commodity: string
  targetDate?: string | null
  accountIds: number[]
  manualAmount?: string | null
  notes?: string | null
  archived?: boolean
}
