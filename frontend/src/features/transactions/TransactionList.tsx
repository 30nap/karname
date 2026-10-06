import { ArrowLeftRight, Scale } from 'lucide-react'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { Badge } from '@/components/ui/badge'
import { useFormat } from '@/app/preferences'
import type { Transaction } from '@/lib/api/types'
import { formatJalaliWithWeekday, toJalali, todayIso } from '@/lib/jalali'
import { SOURCE_LABELS, TRANSACTION_TYPE_LABELS } from '@/lib/labels'
import { cn } from '@/lib/cn'
import { useTransactionDialog } from './TransactionDialog'

function title(t: Transaction, perspective?: number) {
  if (t.description) return t.description
  if (t.type === 'TRANSFER' && perspective !== undefined && t.toAccount?.id === perspective) return `انتقال از ${t.account.name}`
  if (t.type === 'TRANSFER') return `انتقال به ${t.toAccount?.name ?? ''}`
  if (t.category) return t.category.name
  return TRANSACTION_TYPE_LABELS[t.type]
}

function subtitle(t: Transaction) {
  const parts: string[] = []
  if (t.type === 'TRANSFER') parts.push(`${t.account.name} ← ${t.toAccount?.name ?? ''}`)
  else {
    if (t.category && t.description) parts.push(t.category.parentName ? `${t.category.parentName} / ${t.category.name}` : t.category.name)
    if (!t.category && (t.type === 'EXPENSE' || t.type === 'INCOME')) parts.push('بدون دسته‌بندی')
    parts.push(t.account.name)
  }
  return parts.join(' | ')
}

/** A transfer's amounts; seen from one of its accounts it reads as money in (+) or out (−). */
function TransferAmounts({ t, perspective }: { t: Transaction; perspective?: number }) {
  const to = t.toAccount
  const exchange = to !== null && to.commodity !== t.account.commodity
  if (to && perspective === to.id) {
    return (
      <>
        <Amount value={t.toAmount} commodity={to.commodity} sign="+" className="text-sm font-semibold" />
        {exchange ? <Amount value={t.amount} commodity={t.account.commodity} className="text-xs text-muted-foreground" /> : null}
      </>
    )
  }
  return (
    <>
      <Amount value={t.amount} commodity={t.account.commodity} sign={perspective === t.account.id ? '-' : undefined} className="text-sm font-semibold" />
      {exchange && to ? <Amount value={t.toAmount} commodity={to.commodity} className="text-xs text-muted-foreground" /> : null}
      {t.fee && Number(t.fee) > 0 ? (
        <span className="text-xs text-muted-foreground">کارمزد <Amount value={t.fee} commodity={t.account.commodity} /></span>
      ) : null}
    </>
  )
}

/**
 * One transaction. With {@code perspective} (an account id, on that account's page) transfers show
 * the side that touches that account.
 */
export function TransactionRow({ t, showDate, perspective }: { t: Transaction; showDate?: boolean; perspective?: number }) {
  const open = useTransactionDialog()
  const f = useFormat()
  const isFlow = t.type === 'INCOME' || t.type === 'EXPENSE'
  return (
    <button
      type="button"
      onClick={() => open({ transaction: t })}
      className="flex w-full cursor-pointer items-center gap-3 rounded-lg px-2 py-2.5 text-start transition-colors hover:bg-accent/60"
    >
      <span
        className={cn(
          'flex size-10 shrink-0 items-center justify-center rounded-full',
          t.type === 'EXPENSE' && 'bg-expense/10 text-expense',
          t.type === 'INCOME' && 'bg-income/10 text-income',
          t.type === 'TRANSFER' && 'bg-transfer/10 text-transfer',
          !isFlow && t.type !== 'TRANSFER' && 'bg-muted text-muted-foreground',
        )}
      >
        {t.type === 'TRANSFER' ? <ArrowLeftRight className="size-4" /> : isFlow ? <CategoryIcon name={t.category?.icon} /> : <Scale className="size-4" />}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="truncate text-sm font-medium">{title(t, perspective)}</span>
          {t.source !== 'MANUAL' && t.source !== 'SYSTEM' ? <Badge variant="outline" className="shrink-0">{SOURCE_LABELS[t.source]}</Badge> : null}
        </span>
        <span className="block truncate text-xs text-muted-foreground">
          {showDate ? `${f.dateShort(t.date)} | ` : ''}{subtitle(t)}
        </span>
      </span>
      <span className="flex shrink-0 flex-col items-end">
        {t.type === 'EXPENSE' ? <Amount value={t.amount} commodity={t.account.commodity} tone="expense" sign="-" className="text-sm font-semibold" /> : null}
        {t.type === 'INCOME' ? <Amount value={t.amount} commodity={t.account.commodity} tone="income" sign="+" className="text-sm font-semibold" /> : null}
        {t.type === 'TRANSFER' ? <TransferAmounts t={t} perspective={perspective} /> : null}
        {!isFlow && t.type !== 'TRANSFER' ? <Amount value={t.amount} commodity={t.account.commodity} tone="auto" className="text-sm font-semibold" /> : null}
      </span>
    </button>
  )
}

/** Transactions grouped under Jalali day headings (with the year when it isn't the current one). */
export function TransactionList({ items, perspective }: { items: Transaction[]; perspective?: number }) {
  const f = useFormat()
  const currentYear = toJalali(todayIso()).year
  const heading = (iso: string) => {
    const year = toJalali(iso).year
    const day = formatJalaliWithWeekday(iso, f.prefs.digits)
    return year === currentYear ? day : `${day} ${f.digits(year)}`
  }
  const groups: { date: string; items: Transaction[] }[] = []
  for (const t of items) {
    const last = groups[groups.length - 1]
    if (last && last.date === t.date) last.items.push(t)
    else groups.push({ date: t.date, items: [t] })
  }
  return (
    <div className="flex flex-col gap-3">
      {groups.map((g) => (
        <section key={g.date} aria-label={heading(g.date)}>
          <h3 className="px-2 pb-1 text-xs font-semibold text-muted-foreground">{heading(g.date)}</h3>
          <div className="flex flex-col">
            {g.items.map((t) => <TransactionRow key={t.id} t={t} perspective={perspective} />)}
          </div>
        </section>
      ))}
    </div>
  )
}
