import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PageHeader } from '@/components/ui/page-header'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Amount } from '@/components/finance/Amount'
import { MonthNavigator } from '@/components/finance/MonthNavigator'
import { useFormat } from '@/app/preferences'
import { NetWorthTrend } from '@/features/dashboard/NetWorthTrend'
import { ForecastPanel } from '@/features/forecast/ForecastPanel'
import { AiReportPanel } from '@/features/ai/AiReportPanel'
import { TransactionRow } from '@/features/transactions/TransactionList'
import type { ReportKind } from '@/lib/api/types'
import { addMonthsToKey, currentMonthKey } from '@/lib/jalali'
import { AnomalyAlerts, CategoryBreakdown } from './CategoryBreakdown'
import { MonthlyChart } from './MonthlyChart'
import { useAnomalies, useCategoryReport, useTopReport } from './api'

type Tab = 'trend' | 'categories' | 'top' | 'wealth' | 'forecast' | 'ai'
const SPANS = ['1', '3', '6', '12'] as const
type Span = (typeof SPANS)[number]

function PeriodControls({ month, span, kind, onChange }: {
  month: string
  span: Span
  kind: ReportKind
  onChange: (changes: Record<string, string | null>) => void
}) {
  const f = useFormat()
  return (
    <div className="flex flex-wrap items-center gap-2">
      <MonthNavigator value={month} onChange={(m) => onChange({ month: m })} max={currentMonthKey()} className="w-full sm:w-64" />
      <Segmented<Span>
        ariaLabel="بازه"
        size="sm"
        value={span}
        onChange={(s) => onChange({ span: s === '1' ? null : s })}
        options={SPANS.map((s) => ({ value: s, label: `${f.number(Number(s))} ماه` }))}
      />
      <Segmented<ReportKind>
        ariaLabel="نوع"
        size="sm"
        value={kind}
        onChange={(k) => onChange({ kind: k === 'EXPENSE' ? null : k })}
        options={[{ value: 'EXPENSE', label: 'هزینه' }, { value: 'INCOME', label: 'درآمد' }]}
      />
    </div>
  )
}

function periodLabel(f: ReturnType<typeof useFormat>, month: string, span: number) {
  return span === 1 ? f.month(month) : `${f.month(addMonthsToKey(month, -(span - 1)))} تا ${f.month(month)}`
}

export function ReportsPage() {
  const f = useFormat()
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab | null) ?? 'trend'
  const month = params.get('month') ?? currentMonthKey()
  const span = (SPANS as readonly string[]).includes(params.get('span') ?? '') ? (params.get('span') as Span) : '1'
  const kind: ReportKind = params.get('kind') === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([k, v]) => (v === null ? next.delete(k) : next.set(k, v)))
    setParams(next, { replace: true })
  }
  // On phones the tab strip scrolls; bring the open tab (e.g. from a link to ?tab=forecast) into view.
  const tabList = useRef<HTMLDivElement>(null)
  useEffect(() => {
    tabList.current?.querySelector('[data-state="active"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }, [tab])
  const spanNumber = Number(span)
  const categories = useCategoryReport(month, spanNumber, kind, tab === 'categories')
  const top = useTopReport(month, spanNumber, kind, 10, tab === 'top')
  const anomalies = useAnomalies(month, tab === 'categories' && spanNumber === 1 && kind === 'EXPENSE')
  const report = categories.data
  const fromMonth = addMonthsToKey(month, -(spanNumber - 1))

  return (
    <>
      <PageHeader title="گزارش‌ها" description="درآمد، هزینه و ثروت شما در طول زمان، و نقدینگی پیش رو" />
      <Tabs value={tab} onValueChange={(t) => update({ tab: t === 'trend' ? null : t })}>
        <TabsList ref={tabList} className="w-full overflow-x-auto sm:w-auto">
          <TabsTrigger value="trend">روند ماهانه</TabsTrigger>
          <TabsTrigger value="categories">دسته‌ها</TabsTrigger>
          <TabsTrigger value="top">بزرگ‌ترین‌ها</TabsTrigger>
          <TabsTrigger value="wealth">ثروت</TabsTrigger>
          <TabsTrigger value="forecast">پیش‌بینی نقدینگی</TabsTrigger>
          <TabsTrigger value="ai">گزارش هوشمند</TabsTrigger>
        </TabsList>

        <TabsContent value="trend">
          <MonthlyChart months={12} />
        </TabsContent>

        <TabsContent value="categories" className="grid gap-4">
          <PeriodControls month={month} span={span} kind={kind} onChange={update} />
          {spanNumber === 1 && kind === 'EXPENSE' && anomalies.data ? <AnomalyAlerts anomalies={anomalies.data} /> : null}
          <Card className={categories.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
            <CardHeader className="flex-row flex-wrap items-baseline justify-between gap-2">
              <CardTitle>{kind === 'EXPENSE' ? 'هزینه‌ها' : 'درآمدها'} به تفکیک دسته | {periodLabel(f, month, spanNumber)}</CardTitle>
              {report ? (
                <p className="text-sm text-muted-foreground">
                  جمع: <Amount value={report.totalToman} className="font-semibold text-foreground" />
                  {Number(report.previousTotalToman) > 0 ? <> | دوره‌ی قبل: <Amount value={report.previousTotalToman} compact /></> : null}
                </p>
              ) : null}
            </CardHeader>
            <CardContent>
              <CategoryBreakdown report={report} isPending={categories.isPending} fromMonth={fromMonth} toMonth={month} />
              {report && report.unpricedCount > 0 ? (
                <p className="mt-3 text-xs text-warning">{f.number(report.unpricedCount)} تراکنش به‌خاطر نبود قیمت در این گزارش نیامده است.</p>
              ) : null}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="top" className="grid gap-4">
          <PeriodControls month={month} span={span} kind={kind} onChange={update} />
          <Card className={top.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
            <CardHeader>
              <CardTitle>بزرگ‌ترین {kind === 'EXPENSE' ? 'هزینه‌ها' : 'درآمدها'} | {periodLabel(f, month, spanNumber)}</CardTitle>
            </CardHeader>
            <CardContent className="p-2 sm:p-3">
              {top.isPending ? <Skeleton className="h-64" /> : !top.data?.length ? (
                <p className="py-10 text-center text-sm text-muted-foreground">موردی در این بازه نیست.</p>
              ) : (
                <ol className="divide-y">
                  {top.data.map((item, i) => (
                    <li key={item.transaction.id} className="flex items-center gap-1">
                      <span className="w-6 shrink-0 text-center text-xs text-muted-foreground tabular">{f.number(i + 1)}</span>
                      <div className="min-w-0 flex-1">
                        <TransactionRow t={item.transaction} showDate />
                        {item.transaction.account.commodity !== 'IRT' ? (
                          <p className="-mt-2 pb-2 pe-2 text-end text-xs text-muted-foreground">≈ <Amount value={item.valueToman} /></p>
                        ) : null}
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="wealth">
          <NetWorthTrend months={24} chartHeight="h-80" />
        </TabsContent>

        <TabsContent value="forecast">
          <ForecastPanel />
        </TabsContent>

        <TabsContent value="ai">
          <AiReportPanel month={month} onMonth={(m) => update({ month: m })} />
        </TabsContent>
      </Tabs>
    </>
  )
}
