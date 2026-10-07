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

export type LoanMethod = 'ANNUITY' | 'EQUAL_PRINCIPAL'
export type InstallmentStatus = 'PAID_BEFORE' | 'PAID' | 'OVERDUE' | 'DUE_SOON' | 'UPCOMING'

export interface Installment {
  number: number
  dueDate: string
  amount: string
  principal: string
  interest: string
  balanceAfter: string
  status: InstallmentStatus
  paidOn: string | null
  paidAmount: string | null
}

export interface Loan {
  id: number
  accountId: number
  name: string
  bank: string | null
  counterparty: string | null
  principal: string
  annualRate: string
  termMonths: number
  firstDueDate: string
  method: LoanMethod
  installmentAmount: string | null
  paidBefore: number
  paymentAccountId: number | null
  notes: string | null
  outstanding: string
  totalInterest: string
  remainingInterest: string
  paidCount: number
  overdueCount: number
  overdueAmount: string
  next: Installment | null
  endDate: string | null
  installments: Installment[] | null
}

export interface LoanInput {
  name: string
  bank?: string | null
  counterparty?: string | null
  principal: string
  annualRate: string
  termMonths: number
  firstDueDate: string
  method: LoanMethod
  installmentAmount?: string | null
  paymentAccountId?: number | null
  notes?: string | null
  start?: 'NEW' | 'EXISTING'
  depositAccountId?: number | null
  receivedOn?: string | null
  paidBefore?: number | null
}

export interface LoanPreview {
  firstInstallment: string
  lastInstallment: string
  totalInterest: string
  totalPaid: string
  endDate: string
}

export type Frequency = 'WEEKLY' | 'MONTHLY' | 'YEARLY'
export type RecurringMode = 'AUTO' | 'REMIND'
export type OccurrenceStatus = 'POSTED' | 'SKIPPED' | 'DUE' | 'UPCOMING'

export interface RecurringRule {
  id: number
  name: string
  type: 'INCOME' | 'EXPENSE' | 'TRANSFER'
  accountId: number
  toAccountId: number | null
  amount: string
  toAmount: string | null
  categoryId: number | null
  description: string | null
  frequency: Frequency
  interval: number
  dayOfMonth: number | null
  dayOfWeek: number | null
  monthOfYear: number | null
  startDate: string
  endDate: string | null
  mode: RecurringMode
  active: boolean
  nextDate: string | null
  lastPosted: string | null
  dueCount: number
}

export type RecurringInput = Omit<RecurringRule, 'id' | 'nextDate' | 'lastPosted' | 'dueCount'>

export interface Occurrence {
  ruleId: number
  name: string
  type: 'INCOME' | 'EXPENSE' | 'TRANSFER'
  mode: RecurringMode
  date: string
  amount: string
  toAmount: string | null
  accountId: number
  toAccountId: number | null
  categoryId: number | null
  status: OccurrenceStatus
  transactionId: number | null
}

export type ChequeDirection = 'ISSUED' | 'RECEIVED'
export type ChequeStatus = 'PENDING' | 'CLEARED' | 'BOUNCED' | 'CANCELLED'

export interface Cheque {
  id: number
  direction: ChequeDirection
  status: ChequeStatus
  sayadId: string | null
  serial: string | null
  bank: string | null
  accountId: number | null
  counterAccountId: number | null
  categoryId: number | null
  counterparty: string | null
  amount: string
  issueDate: string | null
  dueDate: string
  settledOn: string | null
  description: string | null
  notes: string | null
  transactionId: number | null
  overdue: boolean
  daysToDue: number
}

export type ChequeInput = Omit<Cheque, 'id' | 'status' | 'settledOn' | 'transactionId' | 'overdue' | 'daysToDue'>

export type ForecastSource = 'RECORDED' | 'RECURRING' | 'LOAN' | 'CHEQUE'

export interface ForecastEvent {
  date: string
  source: ForecastSource
  title: string
  amount: string
  overdue: boolean
  link: string
}

export interface Forecast {
  from: string
  to: string
  startBalance: string
  endBalance: string
  minBalance: string
  minDate: string
  inflow: string
  outflow: string
  events: ForecastEvent[]
  points: { date: string; balance: string }[]
}

export type NotificationSeverity = 'INFO' | 'WARNING' | 'CRITICAL'

export interface AppNotification {
  id: number
  type: string
  severity: NotificationSeverity
  title: string
  body: string | null
  link: string | null
  createdAt: string
  read: boolean
}

export type PriceSourceKind = 'NOBITEX' | 'JSON'
export type PriceUnit = 'RIAL' | 'TOMAN'

export interface PriceMapping {
  commodity: string
  /** Coin symbol for Nobitex (usdt), JSON Pointer for other APIs (/data/gold18/price). */
  path: string
  multiplier: string | null
}

export interface PriceSource {
  id: number
  name: string
  kind: PriceSourceKind
  url: string | null
  unit: PriceUnit
  headers: { name: string; hasValue: boolean }[]
  mappings: PriceMapping[]
  intervalMinutes: number
  enabled: boolean
  lastRunAt: string | null
  lastSuccessAt: string | null
  lastError: string | null
  lastCount: number | null
  nextRunAt: string | null
}

export interface PriceSourceInput {
  name: string
  kind: PriceSourceKind
  url: string | null
  unit: PriceUnit
  /** A header without a value keeps its stored value. */
  headers: { name: string; value: string | null }[]
  mappings: PriceMapping[]
  intervalMinutes: number
  enabled: boolean
}

export interface QuoteResult {
  commodity: string
  path: string
  raw: string | null
  priceToman: string | null
  error: string | null
}

export interface PriceRunResult {
  error: string | null
  results: QuoteResult[]
  recorded: number
}

export type DateStyle = 'AUTO' | 'JALALI' | 'GREGORIAN'

export interface ImportMapping {
  date: number | null
  description: number | null
  amount: number | null
  debit: number | null
  credit: number | null
  dateStyle: DateStyle
  unit: PriceUnit
  hasHeader: boolean
}

export interface ImportRow {
  line: number
  date: string | null
  /** Signed Toman: negative for money out. */
  amount: string | null
  description: string | null
  categoryId: number | null
  duplicate: boolean
  ref: string | null
  error: string | null
}

export interface ImportPreview {
  headers: string[]
  sample: string[][]
  mapping: ImportMapping
  rows: ImportRow[]
}

export interface ImportCommitResult {
  created: number
  skipped: number
}

export interface RestoreSummary {
  rows: Record<string, number>
}

// ---------------------------------------------------------------- AI

export type AiTask = 'CHAT' | 'EXTRACT' | 'REPORT'
export type AiEffort = 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX'
export type AiConfidence = 'HIGH' | 'MEDIUM' | 'LOW'

export interface AiStatus {
  /** The user's own switch. */
  enabled: boolean
  shareDescriptions: boolean
  /** Which tasks have a provider configured. */
  tasks: Record<AiTask, boolean>
  quota: { limit: number; used: number }
}

/** A transaction read by the AI, waiting for the user to confirm it. */
export interface AiDraft {
  ref: string
  type: 'EXPENSE' | 'INCOME' | 'TRANSFER'
  date: string
  accountId: number | null
  amount: string | null
  toAccountId: number | null
  toAmount: string | null
  categoryId: number | null
  description: string | null
  confidence: AiConfidence
  warnings: string[]
  duplicate: boolean
  recorded: boolean
}

export interface AiDraftInput {
  ref: string
  type: AiDraft['type']
  date: string
  accountId: number | null
  amount: string | null
  toAccountId: number | null
  toAmount: string | null
  categoryId: number | null
  description: string | null
}

export interface QuickAddResult {
  drafts: AiDraft[]
  note: string | null
}

export interface SmsResult {
  messages: number
  drafts: { message: number; draft: AiDraft }[]
  ignored: { message: number; reason: string }[]
}

export interface DraftCommitResult {
  created: number
  skipped: number
}

export interface ConversationSummary {
  id: number
  title: string
  model: string
  providerName: string | null
  /** False when the provider or model it started on is gone: the history stays readable. */
  available: boolean
  turns: number
  updatedAt: string
}

export type ChatItemKind = 'user' | 'assistant' | 'tool' | 'drafts' | 'error'
export type ToolStatus = 'running' | 'done' | 'error'

export interface ChatItem {
  kind: ChatItemKind
  turn: number
  text?: string | null
  toolId?: string | null
  tool?: string | null
  label?: string | null
  status?: ToolStatus | null
  drafts?: AiDraft[] | null
}

export interface ConversationView {
  id: number
  title: string
  model: string
  providerName: string | null
  available: boolean
  full: boolean
  running: boolean
  items: ChatItem[]
}

export interface MonthlyAiReport {
  headline: string
  summary: string
  highlights: { title: string; detail: string; tone: 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE' }[]
  suggestions: { title: string; detail: string }[]
  createdAt: string
  model: string | null
}

export interface AiReportView {
  month: string
  label: string
  hasData: boolean
  report: MonthlyAiReport | null
  /** The figures changed since the report was written. */
  stale: boolean
}

export interface CategorizeSuggestion {
  transactionId: number
  type: TransactionType
  date: string
  amount: string
  unit: string | null
  description: string
  categoryId: number
  categoryName: string
  confidence: AiConfidence
}

export interface CategorizeResult {
  considered: number
  remaining: number
  suggestions: CategorizeSuggestion[]
}

export type AiProviderKind = 'ANTHROPIC' | 'OPENAI_COMPATIBLE' | 'FAKE'
export type AiPresetId = 'ANTHROPIC' | 'OPENAI' | 'GEMINI' | 'DEEPSEEK' | 'OPENROUTER' | 'GROQ' | 'MISTRAL' | 'XAI' | 'OLLAMA'
  | 'LM_STUDIO' | 'CUSTOM' | 'FAKE'

export interface AiPreset {
  id: AiPresetId
  kind: AiProviderKind
  label: string
  baseUrl: string
  defaultModel: string | null
  supportsTools: boolean
  supportsJsonSchema: boolean
  streamUsage: boolean
  needsKey: boolean
}

export interface AiPresets {
  presets: AiPreset[]
  /** ANTHROPIC_API_KEY is set on the server. */
  envKeyAvailable: boolean
}

export interface AiProvider {
  id: number
  name: string
  kind: AiProviderKind
  preset: AiPresetId
  baseUrl: string
  hasApiKey: boolean
  keyFromEnv: boolean
  headers: { name: string; hasValue: boolean }[]
  queryParams: Record<string, string>
  defaultModel: string | null
  supportsTools: boolean
  supportsJsonSchema: boolean
  streamUsage: boolean
  refusalFallback: boolean
  enabled: boolean
  usedBy: AiTask[]
}

export interface AiProviderInput {
  name: string
  preset: AiPresetId
  baseUrl?: string
  apiKey?: string
  clearApiKey?: boolean
  useEnvKey?: boolean
  /** An empty value keeps the stored one. */
  headers?: { name: string; value: string }[]
  queryParams?: Record<string, string>
  defaultModel?: string
  supportsTools?: boolean
  supportsJsonSchema?: boolean
  streamUsage?: boolean
  refusalFallback?: boolean
  enabled?: boolean
}

export interface AiRoute {
  task: AiTask
  providerId: number | null
  providerName: string | null
  model: string | null
  effort: AiEffort | null
  defaultEffort: AiEffort
}

export interface AiRouteInput {
  task: AiTask
  providerId: number | null
  model: string | null
  effort: AiEffort | null
}

export interface AiTestResult {
  ok: boolean
  latencyMs: number
  model: string | null
  /** Null when tools are off for this provider. */
  toolCalling: boolean | null
  reply: string | null
  error: string | null
}

export interface AiUsageDay {
  day: string
  task: string
  operations: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  costUsd: string | null
  failures: number
}
