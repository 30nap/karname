import { Lightbulb, RefreshCw, Sparkles, TrendingDown, TrendingUp, Minus } from 'lucide-react'
import { useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { MonthNavigator } from '@/components/finance/MonthNavigator'
import { useFormat } from '@/app/preferences'
import { ApiError } from '@/lib/api/client'
import type { MonthlyAiReport } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { currentMonthKey } from '@/lib/jalali'
import { AiMarkdown } from './Markdown'
import { AiUnavailable } from './AiUnavailable'
import { useAiStatus, useGenerateAiReport, useMonthlyAiReport } from './api'
import { useAiText } from './text'

const TONES = {
  POSITIVE: { icon: TrendingUp, className: 'border-income/25 bg-income/5 [&_svg]:text-income' },
  NEGATIVE: { icon: TrendingDown, className: 'border-expense/25 bg-expense/5 [&_svg]:text-expense' },
  NEUTRAL: { icon: Minus, className: '[&_svg]:text-muted-foreground' },
} as const

/** The AI's narrative of a month: written on request, kept, and flagged when the figures change. */
export function AiReportPanel({ month, onMonth }: { month: string; onMonth: (month: string) => void }) {
  const f = useFormat()
  const { data: status } = useAiStatus()
  const report = useMonthlyAiReport(month)
  const generate = useGenerateAiReport()
  const [error, setError] = useState<string | null>(null)
  const view = report.data
  const write = () => {
    setError(null)
    generate.mutate(month, { onError: (e) => setError(e instanceof ApiError ? e.message : 'گزارش نوشته نشد.') })
  }
  const blocked = status && (!status.enabled || !status.tasks.REPORT)

  return (
    <div className="grid grid-cols-1 gap-4">
      <MonthNavigator value={month} onChange={(m) => { setError(null); onMonth(m) }} max={currentMonthKey()} className="w-full sm:w-64" />
      {blocked ? <AiUnavailable status={status} task="REPORT" /> : report.isPending ? <Skeleton className="h-64" /> : !view ? null : (
        <>
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          {generate.isPending ? (
            <Alert><Sparkles className="animate-pulse" />در حال نوشتن گزارش {view.label}… ممکن است تا یک دقیقه طول بکشد.</Alert>
          ) : null}
          {!view.hasData ? (
            <EmptyState icon={Sparkles} title={`برای ${view.label} تراکنشی ثبت نشده`}
              description="گزارش هوشمند از درآمد و هزینه‌های ثبت‌شده‌ی ماه نوشته می‌شود." />
          ) : !view.report ? (
            <EmptyState icon={Sparkles} title={`گزارش هوشمند ${view.label}`}
              description="دستیار ارقام این ماه را با ماه قبل و میانگین سه ماه پیش مقایسه می‌کند، نکته‌های مهم را می‌گوید و چند پیشنهاد عملی می‌دهد."
              action={<Button onClick={write} loading={generate.isPending}><Sparkles />نوشتن گزارش</Button>} />
          ) : (
            <ReportView report={view.report} stale={view.stale} label={view.label} busy={generate.isPending} onRewrite={write}
              meta={`${f.dateTime(view.report.createdAt)}${view.report.model ? ` | ${view.report.model}` : ''}`} />
          )}
        </>
      )}
    </div>
  )
}

function ReportView({ report, stale, label, meta, busy, onRewrite }: {
  report: MonthlyAiReport
  stale: boolean
  label: string
  meta: string
  busy: boolean
  onRewrite: () => void
}) {
  const text = useAiText()
  return (
    <div className={cn('grid grid-cols-1 gap-4', busy && 'opacity-60 transition-opacity')}>
      {stale ? (
        <Alert variant="warning" className="items-center">
          <RefreshCw />
          <span className="flex-1">از زمان نوشتن این گزارش، اطلاعات {label} تغییر کرده است.</span>
          <Button size="sm" variant="outline" onClick={onRewrite} loading={busy}>به‌روزرسانی</Button>
        </Alert>
      ) : null}
      <Card>
        <CardHeader className="gap-1">
          <CardTitle className="text-lg leading-8">{text(report.headline)}</CardTitle>
          <p className="text-xs text-muted-foreground"><bdi>{meta}</bdi></p>
        </CardHeader>
        <CardContent><AiMarkdown>{report.summary}</AiMarkdown></CardContent>
      </Card>
      {report.highlights.length ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {report.highlights.map((h) => {
            const tone = TONES[h.tone] ?? TONES.NEUTRAL
            return (
              <div key={h.title} className={cn('grid grid-cols-1 gap-1 rounded-xl border p-3', tone.className)}>
                <p className="flex items-center gap-2 text-sm font-semibold"><tone.icon className="size-4 shrink-0" />{text(h.title)}</p>
                <AiMarkdown className="text-muted-foreground">{h.detail}</AiMarkdown>
              </div>
            )
          })}
        </div>
      ) : null}
      {report.suggestions.length ? (
        <Card>
          <CardHeader><CardTitle>پیشنهادها</CardTitle></CardHeader>
          <CardContent>
            <ul className="grid gap-3">
              {report.suggestions.map((s) => (
                <li key={s.title} className="flex gap-3">
                  <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-warning/15 text-warning">
                    <Lightbulb className="size-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold">{text(s.title)}</p>
                    <AiMarkdown className="text-muted-foreground">{s.detail}</AiMarkdown>
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>ارقام از خود کارنامه است؛ متن و پیشنهادها را هوش مصنوعی نوشته و ممکن است خطا داشته باشد.</span>
        {stale ? null : <Button size="sm" variant="ghost" onClick={onRewrite} loading={busy}><RefreshCw />نوشتن دوباره</Button>}
      </div>
    </div>
  )
}
