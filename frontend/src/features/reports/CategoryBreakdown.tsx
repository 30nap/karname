import { ArrowDown, ArrowUp, ChevronDown, Sparkles, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Alert } from '@/components/ui/alert'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { chartColor } from '@/components/charts/chart-colors'
import { useFormat } from '@/app/preferences'
import type { Anomaly, CategoryLine, CategoryReport } from '@/lib/api/types'
import { IRT } from '@/lib/format/money'
import { monthRange } from '@/lib/jalali'
import { cn } from '@/lib/cn'

/** Change against the previous period: «▲ ۱۲٪», «▼ ۸٪» or «جدید». */
function Delta({ value, previous }: { value: string; previous: string }) {
  const f = useFormat()
  const now = Number(value)
  const before = Number(previous)
  if (before === 0) {
    return now > 0 ? <span className="text-xs text-muted-foreground">جدید</span> : null
  }
  const change = (now - before) / before
  if (Math.abs(change) < 0.005) return <span className="text-xs text-muted-foreground">بدون تغییر</span>
  const Icon = change > 0 ? ArrowUp : ArrowDown
  return (
    <span className="flex items-center gap-0.5 text-xs text-muted-foreground" title="نسبت به دوره‌ی قبل">
      <Icon className="size-3" aria-label={change > 0 ? 'افزایش' : 'کاهش'} />
      {f.percent(Math.abs(change))}
    </span>
  )
}

function Row({ line, max, color, link, depth = 0 }: { line: CategoryLine; max: number; color: string; link: (id: number | null) => string; depth?: number }) {
  const f = useFormat()
  const [open, setOpen] = useState(false)
  const width = max > 0 ? Math.max((Number(line.valueToman) / max) * 100, Number(line.valueToman) > 0 ? 1 : 0) : 0
  const expandable = line.children.length > 0
  const name = line.name ?? 'بدون دسته‌بندی'
  const average = depth === 0 && line.averageToman !== null && Number(line.averageToman) > 0 ? line.averageToman : null
  return (
    <li>
      <div className="grid gap-1 py-2">
        <div className="flex items-center gap-2">
          {expandable ? (
            <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} aria-label={`زیرمجموعه‌های ${name}`}
              className="flex size-6 shrink-0 cursor-pointer items-center justify-center rounded-md text-muted-foreground hover:bg-accent">
              <ChevronDown className={cn('size-4 transition-transform', !open && 'rotate-90')} />
            </button>
          ) : <span className="size-6 shrink-0" />}
          <span className={cn('flex min-w-0 flex-1 items-center gap-2', depth > 0 && 'ps-5')}>
            <CategoryIcon name={line.icon} className="shrink-0 text-muted-foreground" />
            <Link to={link(line.categoryId)} className="truncate text-sm font-medium hover:underline">{name}</Link>
          </span>
          <Delta value={line.valueToman} previous={line.previousToman} />
          <Amount value={line.valueToman} className="text-sm font-semibold" />
        </div>
        {/* Every row's track has the same width, so bar lengths compare across rows and levels. */}
        <div className="flex items-center gap-2 ps-8">
          <div className="h-2 flex-1 overflow-hidden rounded-[4px] bg-muted">
            <div className="h-full rounded-[4px]" style={{ width: `${width}%`, background: color, opacity: depth > 0 ? 0.7 : 1 }} />
          </div>
          <span className="w-11 shrink-0 text-end text-xs text-muted-foreground tabular">{f.percent(Number(line.share), 1)}</span>
        </div>
        {average ? <p className="ps-8 text-xs text-muted-foreground">میانگین ۳ ماه قبل: {f.money(average, IRT, { compact: true })}</p> : null}
      </div>
      {expandable && open ? (
        <ul>{line.children.map((child) => <Row key={child.categoryId ?? 'none'} line={child} max={max} color={color} link={link} depth={depth + 1} />)}</ul>
      ) : null}
    </li>
  )
}

/**
 * Ranked bars, one hue (expense orange, income blue, as in the monthly chart). Values and shares
 * are printed on every row, so the list doubles as the table view.
 */
export function CategoryBreakdown({ report, isPending, fromMonth, toMonth }: {
  report: CategoryReport | undefined
  isPending: boolean
  fromMonth: string
  toMonth: string
}) {
  if (isPending) return <Skeleton className="h-72" />
  if (!report || report.items.length === 0) {
    return <p className="py-10 text-center text-sm text-muted-foreground">در این بازه {report?.kind === 'INCOME' ? 'درآمدی' : 'هزینه‌ای'} ثبت نشده است.</p>
  }
  const color = report.kind === 'INCOME' ? chartColor(1) : chartColor(2)
  const max = Math.max(...report.items.map((i) => Number(i.valueToman)))
  const { start } = monthRange(fromMonth)
  const { end } = monthRange(toMonth)
  const link = (id: number | null) => {
    const params = new URLSearchParams({ type: report.kind, from: start, to: end })
    if (id === null) params.set('uncategorized', '1')
    else params.set('category', String(id))
    return `/transactions?${params}`
  }
  return (
    <ul className="divide-y">
      {report.items.map((line) => <Row key={line.categoryId ?? 'none'} line={line} max={max} color={color} link={link} />)}
    </ul>
  )
}

export function AnomalyAlerts({ anomalies }: { anomalies: Anomaly[] }) {
  const f = useFormat()
  if (anomalies.length === 0) return null
  return (
    <div className="grid gap-2">
      {anomalies.map((a) => (
        <Alert key={a.categoryId} variant="warning">
          {a.ratio === null ? <Sparkles /> : <TriangleAlert />}
          <p>
            {a.ratio === null ? (
              <>این ماه <Amount value={a.currentToman} compact className="font-semibold" /> برای «{a.name}» خرج شده که در {f.number(a.historyMonths)} ماه گذشته سابقه نداشته است.</>
            ) : (
              <>هزینه‌ی «{a.name}» این ماه <span className="font-semibold">{f.number(a.ratio, { maxFraction: 1 })} برابر</span> میانگین {f.number(a.historyMonths)} ماه اخیر است (<Amount value={a.currentToman} compact /> در برابر <Amount value={a.averageToman} compact />).</>
            )}
          </p>
        </Alert>
      ))}
    </div>
  )
}
