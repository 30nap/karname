import { AlertTriangle, ArrowLeft, Check, CircleDot, X } from 'lucide-react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Amount } from '@/components/finance/Amount'
import { useFormat, usePrefs } from '@/app/preferences'
import type { AiProposal, CardLine, ProposalKind } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { toDisplayAmount, unitLabel, IRT } from '@/lib/format/money'
import { numberToPersianWords } from '@/lib/persian/words'
import { useDecideProposal } from './api'

const KIND_LABELS: Record<ProposalKind, string> = {
  ACCOUNT: 'حساب', LOAN: 'وام', LOAN_PAYMENT: 'پرداخت قسط', GOAL: 'هدف', BUDGET: 'بودجه', CHEQUE: 'چک', CHEQUE_STATUS: 'وضعیت چک',
  RECURRING: 'تراکنش تکراری', CATEGORY: 'دسته', PRICE: 'قیمت', SECURITY: 'سهام',
}

/**
 * Records the assistant proposed (a loan, an account, a budget…), each a card the user confirms or
 * dismisses. The card shows every value that will be saved and what the app worked out; nothing
 * is recorded without a click, and confirming twice records once.
 */
export function ProposalList({ proposals, className }: { proposals: AiProposal[]; className?: string }) {
  const decide = useDecideProposal()
  const f = useFormat()
  const pending = proposals.filter((p) => p.status === 'PENDING')

  const applyAll = async () => {
    for (const p of pending) {
      // one after another, each on its own: a refused card does not stop the rest
      await decide.mutateAsync({ ref: p.ref, decision: 'apply' }).catch(() => undefined)
    }
  }

  return (
    <div className={cn('grid grid-cols-1 gap-2', className)}>
      {proposals.map((p) => <ProposalCard key={p.ref} proposal={p} />)}
      {pending.length > 1 ? (
        <Button className="w-fit justify-self-end" loading={decide.isPending} onClick={applyAll}>
          <Check />ثبت هر {f.number(pending.length)} مورد
        </Button>
      ) : null}
    </div>
  )
}

function ProposalCard({ proposal: p }: { proposal: AiProposal }) {
  const decide = useDecideProposal()
  const f = useFormat()
  const busy = decide.isPending && decide.variables?.ref === p.ref
  const done = p.status !== 'PENDING'
  return (
    <div data-proposal={p.kind} className={cn('grid grid-cols-1 gap-2 rounded-xl border bg-card p-3',
      p.status === 'APPLIED' && 'bg-income/5', (p.status === 'DISMISSED' || p.status === 'REPLACED' || p.status === 'EXPIRED') && 'opacity-60')}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Badge>{KIND_LABELS[p.kind] ?? p.kind}</Badge>
        <span className="min-w-0 font-semibold">{f.digits(p.title)}</span>
        <StatusBadge status={p.status} />
      </div>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm">
        {p.lines.map((line) => (
          <div key={line.label} className="contents">
            <dt className="text-muted-foreground">{line.label}</dt>
            <dd className="min-w-0 break-words"><LineValue line={line} /></dd>
          </div>
        ))}
      </dl>
      {p.effects.length > 0 && p.status === 'PENDING' ? (
        <ul className="grid gap-1 text-xs text-muted-foreground">
          {p.effects.map((e) => <li key={e} className="flex items-start gap-1.5"><CircleDot className="mt-0.5 size-3 shrink-0" />{f.digits(e)}</li>)}
        </ul>
      ) : null}
      {p.warnings.length > 0 && p.status === 'PENDING' ? (
        <ul className="grid gap-1 text-xs text-warning">
          {p.warnings.map((w) => <li key={w} className="flex items-start gap-1.5"><AlertTriangle className="mt-0.5 size-3.5 shrink-0" />{f.digits(w)}</li>)}
        </ul>
      ) : null}
      {p.status === 'APPLIED' && p.link ? (
        <Button asChild size="sm" variant="outline" className="w-fit"><Link to={p.link}>مشاهده<ArrowLeft /></Link></Button>
      ) : null}
      {p.status === 'EXPIRED' ? (
        <p className="text-xs text-muted-foreground">این پیشنهاد منقضی شده است؛ اگر هنوز لازم است، از دستیار دوباره بخواهید.</p>
      ) : null}
      {done ? null : (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" loading={busy && decide.variables?.decision === 'apply'} disabled={decide.isPending}
            onClick={() => decide.mutate({ ref: p.ref, decision: 'apply' })}><Check />ثبت</Button>
          <Button size="sm" variant="ghost" disabled={decide.isPending}
            onClick={() => decide.mutate({ ref: p.ref, decision: 'dismiss' })}><X />رد</Button>
          <span className="text-xs text-muted-foreground">برای تغییر، به دستیار بگویید چه چیزی را عوض کند.</span>
        </div>
      )}
    </div>
  )
}

function StatusBadge({ status }: { status: AiProposal['status'] }) {
  switch (status) {
    case 'APPLIED':
      return <Badge variant="income" className="ms-auto"><Check className="size-3" />ثبت شد</Badge>
    case 'DISMISSED':
      return <Badge variant="outline" className="ms-auto">رد شد</Badge>
    case 'REPLACED':
      return <Badge variant="outline" className="ms-auto">جایگزین شد</Badge>
    case 'EXPIRED':
      return <Badge variant="outline" className="ms-auto">منقضی شد</Badge>
    default:
      return null
  }
}

function LineValue({ line }: { line: CardLine }) {
  const f = useFormat()
  const prefs = usePrefs()
  switch (line.format) {
    case 'MONEY': {
      const shown = toDisplayAmount(line.value, 'IRT', prefs)
      const words = line.main && /^\d+$/.test(shown) ? numberToPersianWords(shown) : ''
      return (
        <span className="flex flex-col">
          <Amount value={line.value} className={line.main ? 'font-semibold' : undefined} />
          {words ? <span className="text-xs text-muted-foreground">{words} {unitLabel(IRT, prefs)}</span> : null}
        </span>
      )
    }
    case 'QUANTITY':
      return <Amount value={line.value} commodity={line.unit ?? 'IRT'} className={line.main ? 'font-semibold' : undefined} />
    case 'DATE':
      return <>{f.date(line.value)}</>
    case 'NUMBER':
      return <>{f.number(line.value)}</>
    case 'PERCENT':
      return <bdi dir="rtl">{f.digits(line.value)}٪</bdi>
    default:
      return <bdi>{f.digits(line.value)}</bdi>
  }
}
