import { AlertTriangle, Check, Pencil, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { Segmented } from '@/components/ui/segmented'
import { Amount } from '@/components/finance/Amount'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { AccountSelect, CategorySelect } from '@/components/finance/selects'
import { useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCategories } from '@/features/categories/api'
import { useCommodityMap } from '@/features/commodities/api'
import type { AiDraft, AiDraftInput } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { fromDisplayAmount, toDisplayAmount } from '@/lib/format/money'
import { useCommitDrafts } from './api'

const TYPE_LABELS: Record<AiDraft['type'], string> = { EXPENSE: 'هزینه', INCOME: 'درآمد', TRANSFER: 'انتقال' }
const TYPE_VARIANTS = { EXPENSE: 'expense', INCOME: 'income', TRANSFER: 'transfer' } as const

/** What is missing before a draft can be recorded, or null. */
function missing(d: AiDraft): string | null {
  if (!d.amount || Number(d.amount) <= 0) return 'مبلغ را وارد کنید.'
  if (d.accountId === null) return 'حساب را انتخاب کنید.'
  if (d.type === 'TRANSFER' && (d.toAccountId === null || !d.toAmount)) return 'حساب مقصد و مقدار دریافتی را مشخص کنید.'
  return null
}

function toInput(d: AiDraft): AiDraftInput {
  return {
    ref: d.ref, type: d.type, date: d.date, accountId: d.accountId, amount: d.amount, toAccountId: d.toAccountId,
    toAmount: d.toAmount, categoryId: d.type === 'TRANSFER' ? null : d.categoryId, description: d.description,
  }
}

/**
 * Transactions the AI read, for the user to check, edit and record. Nothing is recorded without a
 * click; recording twice is harmless (each draft has its own reference).
 */
export function DraftList({ drafts, labels, className }: {
  drafts: AiDraft[]
  /** A caption per draft, e.g. which SMS it came from. */
  labels?: (string | null)[]
  className?: string
}) {
  const [edited, setEdited] = useState<Record<string, AiDraft>>({})
  const [dismissed, setDismissed] = useState<Set<string>>(new Set())
  const [recorded, setRecorded] = useState<Set<string>>(new Set())
  const commit = useCommitDrafts()
  const f = useFormat()

  const current = drafts.map((d) => {
    const draft = edited[d.ref] ?? d
    return recorded.has(d.ref) ? { ...draft, recorded: true } : draft
  })
  const pending = current.filter((d) => !d.recorded && !dismissed.has(d.ref))
  const ready = pending.filter((d) => missing(d) === null)

  const record = (list: AiDraft[]) => {
    commit.mutate(list.map(toInput), {
      onSuccess: () => setRecorded((s) => new Set([...s, ...list.map((d) => d.ref)])),
    })
  }

  return (
    <div className={cn('grid grid-cols-1 gap-2', className)}>
      {current.map((d, i) => dismissed.has(d.ref) ? null : (
        <DraftCard key={d.ref} draft={d} label={labels?.[i] ?? null} busy={commit.isPending}
          onChange={(next) => setEdited((e) => ({ ...e, [d.ref]: next }))}
          onRecord={() => record([d])}
          onDismiss={() => setDismissed((s) => new Set([...s, d.ref]))} />
      ))}
      {ready.length > 1 ? (
        <Button className="w-fit justify-self-end" loading={commit.isPending} onClick={() => record(ready)}>
          <Check />ثبت {f.number(ready.length)} تراکنش
        </Button>
      ) : null}
    </div>
  )
}

function DraftCard({ draft, label, busy, onChange, onRecord, onDismiss }: {
  draft: AiDraft
  label: string | null
  busy: boolean
  onChange: (draft: AiDraft) => void
  onRecord: () => void
  onDismiss: () => void
}) {
  const f = useFormat()
  const [editing, setEditing] = useState(false)
  const { data: accounts = [] } = useAccounts()
  const { data: categories = [] } = useCategories()
  const accountById = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts])
  const categoryById = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])
  const account = draft.accountId === null ? undefined : accountById.get(draft.accountId)
  const toAccount = draft.toAccountId === null ? undefined : accountById.get(draft.toAccountId)
  const category = draft.categoryId === null ? undefined : categoryById.get(draft.categoryId)
  const parent = category?.parentId ? categoryById.get(category.parentId) : undefined
  const problem = missing(draft)

  return (
    <div className={cn('grid grid-cols-1 gap-2 rounded-xl border bg-card p-3', draft.recorded && 'bg-income/5')}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Badge variant={TYPE_VARIANTS[draft.type]}>{TYPE_LABELS[draft.type]}</Badge>
        <Amount value={draft.amount} commodity={account?.commodity ?? 'IRT'} className="font-semibold" />
        {draft.type === 'TRANSFER' && toAccount && toAccount.commodity !== account?.commodity ? (
          <span className="text-sm text-muted-foreground">← <Amount value={draft.toAmount} commodity={toAccount.commodity} /></span>
        ) : null}
        {label ? <span className="text-xs text-muted-foreground">{label}</span> : null}
        {draft.duplicate && !draft.recorded ? <Badge variant="warning">احتمالاً تکراری</Badge> : null}
        {draft.recorded ? <Badge variant="income" className="ms-auto"><Check className="size-3" />ثبت شد</Badge> : null}
      </div>
      <p className="text-sm text-muted-foreground">
        {draft.description ? <span className="text-foreground">{draft.description}</span> : null}
        {draft.description ? ' | ' : ''}
        {f.date(draft.date)}
        {' | '}
        {account?.name ?? 'بدون حساب'}
        {draft.type === 'TRANSFER' ? ` ← ${toAccount?.name ?? 'بدون حساب مقصد'}` : category ? ` | ${parent ? `${parent.name} › ` : ''}${category.name}` : ''}
      </p>
      {!draft.recorded && draft.warnings.length > 0 ? (
        <ul className="grid gap-1 text-xs text-warning">
          {draft.warnings.map((w) => <li key={w} className="flex items-start gap-1.5"><AlertTriangle className="mt-0.5 size-3.5 shrink-0" />{w}</li>)}
        </ul>
      ) : null}
      {editing && !draft.recorded ? <DraftEditor draft={draft} onChange={onChange} /> : null}
      {draft.recorded ? null : (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={onRecord} disabled={problem !== null} loading={busy}><Check />ثبت</Button>
          <Button size="sm" variant="outline" onClick={() => setEditing((e) => !e)}><Pencil />{editing ? 'بستن' : 'ویرایش'}</Button>
          <Button size="sm" variant="ghost" onClick={onDismiss}><X />رد</Button>
          {problem ? <span className="text-xs text-destructive">{problem}</span> : null}
        </div>
      )}
    </div>
  )
}

function DraftEditor({ draft, onChange }: { draft: AiDraft; onChange: (draft: AiDraft) => void }) {
  const f = useFormat()
  const commodities = useCommodityMap()
  const { data: accounts = [] } = useAccounts()
  const commodityOf = (id: number | null) => accounts.find((a) => a.id === id)?.commodity ?? 'IRT'
  const from = commodityOf(draft.accountId)
  const to = commodityOf(draft.toAccountId)
  const display = (amount: string | null, commodity: string) => (amount ? toDisplayAmount(amount, commodity, f.prefs) : '')
  const stored = (value: string, commodity: string) => (value ? fromDisplayAmount(value, commodity, f.prefs) : null)
  return (
    <div className="grid grid-cols-1 gap-3 rounded-lg bg-muted/40 p-3 sm:grid-cols-2">
      <div className="sm:col-span-2">
        <Segmented<AiDraft['type']> ariaLabel="نوع تراکنش" size="sm" value={draft.type}
          onChange={(type) => onChange({ ...draft, type, categoryId: null })}
          options={[{ value: 'EXPENSE', label: 'هزینه' }, { value: 'INCOME', label: 'درآمد' }, { value: 'TRANSFER', label: 'انتقال' }]} />
      </div>
      <FormField label="مبلغ">
        <MoneyInput value={display(draft.amount, from)} commodity={commodities.get(from)} showWords={false}
          onChange={(v) => onChange({ ...draft, amount: stored(v, from) })} />
      </FormField>
      <FormField label={draft.type === 'INCOME' ? 'به حساب' : 'از حساب'}>
        <AccountSelect value={draft.accountId} onChange={(accountId) => onChange({ ...draft, accountId })} />
      </FormField>
      {draft.type === 'TRANSFER' ? (
        <>
          <FormField label="به حساب">
            <AccountSelect value={draft.toAccountId} exclude={draft.accountId} onChange={(toAccountId) => onChange({ ...draft, toAccountId })} />
          </FormField>
          <FormField label="مقدار دریافتی">
            <MoneyInput value={display(draft.toAmount ?? (from === to ? draft.amount : null), to)} commodity={commodities.get(to)} showWords={false}
              onChange={(v) => onChange({ ...draft, toAmount: stored(v, to) })} />
          </FormField>
        </>
      ) : (
        <FormField label="دسته‌بندی">
          <CategorySelect kind={draft.type === 'INCOME' ? 'INCOME' : 'EXPENSE'} value={draft.categoryId}
            onChange={(categoryId) => onChange({ ...draft, categoryId })} />
        </FormField>
      )}
      <FormField label="تاریخ">
        <JalaliDatePicker value={draft.date} onChange={(date) => onChange({ ...draft, date })} />
      </FormField>
      <FormField label="شرح" optional className="sm:col-span-2">
        <Input value={draft.description ?? ''} maxLength={300} onChange={(e) => onChange({ ...draft, description: e.target.value })} />
      </FormField>
    </div>
  )
}
