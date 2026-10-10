import Big from 'big.js'
import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { AXIS_TICK, chartColor } from '@/components/charts/chart-colors'
import { ChartTooltipBox, ChartViewToggle, TooltipRow, type ChartView } from '@/components/charts/chart-utils'
import { useFormat } from '@/app/preferences'
import type { MonthTotals } from '@/lib/api/types'
import { IRT, toDisplayAmount, unitLabel } from '@/lib/format/money'
import { JALALI_MONTHS, parseMonthKey } from '@/lib/jalali'
import { fromFirstData } from '@/lib/series'
import { useMonthlyReport } from './api'

const INCOME = chartColor(1)
const EXPENSE = chartColor(2)

function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
      <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[3px]" style={{ background: INCOME }} aria-hidden />درآمد</span>
      <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[3px]" style={{ background: EXPENSE }} aria-hidden />هزینه</span>
      <span>ستون‌های کم‌رنگ: ماه جاری تا امروز</span>
    </div>
  )
}

/** Averages of the complete months (the current, partial month is left out). */
function Averages({ rows }: { rows: MonthTotals[] }) {
  const f = useFormat()
  const complete = rows.filter((r) => !r.partial && (Number(r.incomeToman) > 0 || Number(r.expenseToman) > 0))
  if (complete.length === 0) return null
  const sum = (key: 'incomeToman' | 'expenseToman') => complete.reduce((acc, r) => acc.plus(r[key]), new Big(0))
  const income = sum('incomeToman')
  const expense = sum('expenseToman')
  const n = complete.length
  const rate = income.gt(0) ? Number(income.minus(expense).div(income).toFixed(4)) : null
  return (
    <div className="grid grid-cols-3 gap-3 rounded-xl bg-muted/50 p-3 text-sm">
      <div>
        <p className="text-xs text-muted-foreground">میانگین درآمد ماهانه</p>
        <Amount value={income.div(n).round(0).toFixed()} compact className="font-semibold" />
      </div>
      <div>
        <p className="text-xs text-muted-foreground">میانگین هزینه‌ی ماهانه</p>
        <Amount value={expense.div(n).round(0).toFixed()} compact className="font-semibold" />
      </div>
      <div>
        <p className="text-xs text-muted-foreground">نرخ پس‌انداز</p>
        <span className="font-semibold">{rate === null ? '—' : f.percent(rate)}</span>
      </div>
      <p className="col-span-3 text-xs text-muted-foreground">بر اساس {f.number(n)} ماه کامل اخیر</p>
    </div>
  )
}

/** Income and expense per Jalali month as grouped bars, with a table view. */
export function MonthlyChart({ months = 12 }: { months?: number }) {
  const f = useFormat()
  const { data, isPending } = useMonthlyReport(months)
  const [view, setView] = useState<ChartView>('chart')
  const rows = useMemo(() => fromFirstData(data ?? [], (r) => Number(r.incomeToman) !== 0 || Number(r.expenseToman) !== 0), [data])
  const plot = useMemo(
    () => rows.map((r) => ({
      ...r,
      income: Number(toDisplayAmount(r.incomeToman, 'IRT', f.prefs)),
      expense: Number(toDisplayAmount(r.expenseToman, 'IRT', f.prefs)),
    })),
    [rows, f.prefs],
  )
  const empty = rows.length === 0
  const monthName = (key: string) => JALALI_MONTHS[parseMonthKey(key).month - 1]

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>درآمد و هزینه‌ی ماهانه</CardTitle>
        <ChartViewToggle value={view} onChange={setView} />
      </CardHeader>
      <CardContent className="grid gap-4">
        {isPending ? <Skeleton className="h-72" /> : empty ? (
          <p className="py-10 text-center text-sm text-muted-foreground">هنوز درآمد یا هزینه‌ای ثبت نشده است.</p>
        ) : view === 'chart' ? (
          <>
            <Legend />
            <div dir="ltr" className="h-72 w-full" role="img" aria-label="نمودار ستونی درآمد و هزینه‌ی ماهانه">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={plot} barGap={2} barCategoryGap="24%" margin={{ top: 8, right: 4, left: 4, bottom: 0 }}>
                  <CartesianGrid vertical={false} stroke="var(--chart-grid)" />
                  <XAxis dataKey="month" tickFormatter={monthName} tick={AXIS_TICK} tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={8} />
                  <YAxis orientation="right" width={64} tick={AXIS_TICK} tickLine={false} axisLine={false} tickFormatter={(v: number) => f.compact(v)} />
                  <Tooltip
                    cursor={{ fill: 'var(--color-muted)', opacity: 0.6 }}
                    content={({ active, payload }) => {
                      const row = active && payload?.length ? (payload[0].payload as MonthTotals) : null
                      if (!row) return null
                      return (
                        <ChartTooltipBox title={`${f.month(row.month)}${row.partial ? ' (تا امروز)' : ''}`}>
                          <TooltipRow color={INCOME} label="درآمد" value={<Amount value={row.incomeToman} />} />
                          <TooltipRow color={EXPENSE} label="هزینه" value={<Amount value={row.expenseToman} />} />
                          <TooltipRow label="خالص" value={<Amount value={row.netToman} tone="auto" />} />
                          {row.savingsRate !== null ? <TooltipRow label="نرخ پس‌انداز" value={f.percent(Number(row.savingsRate))} /> : null}
                        </ChartTooltipBox>
                      )
                    }}
                  />
                  <Bar dataKey="income" name="درآمد" fill={INCOME} radius={[4, 4, 0, 0]} maxBarSize={20} isAnimationActive={false}>
                    {plot.map((r) => <Cell key={r.month} fillOpacity={r.partial ? 0.45 : 1} />)}
                  </Bar>
                  <Bar dataKey="expense" name="هزینه" fill={EXPENSE} radius={[4, 4, 0, 0]} maxBarSize={20} isAnimationActive={false}>
                    {plot.map((r) => <Cell key={r.month} fillOpacity={r.partial ? 0.45 : 1} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[32rem] text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 text-start font-medium">ماه</th>
                  <th className="py-2 text-end font-medium">درآمد</th>
                  <th className="py-2 text-end font-medium">هزینه</th>
                  <th className="py-2 text-end font-medium">خالص</th>
                  <th className="py-2 text-end font-medium">نرخ پس‌انداز</th>
                </tr>
              </thead>
              <tbody className="tabular">
                {[...rows].reverse().map((r) => (
                  <tr key={r.month} className="border-t">
                    <td className="py-2">{f.month(r.month)}{r.partial ? <span className="text-xs text-muted-foreground"> (تا امروز)</span> : null}</td>
                    <td className="py-2 text-end"><Amount value={r.incomeToman} withUnit={false} /></td>
                    <td className="py-2 text-end"><Amount value={r.expenseToman} withUnit={false} /></td>
                    <td className="py-2 text-end"><Amount value={r.netToman} tone="auto" withUnit={false} /></td>
                    <td className="py-2 text-end">{r.savingsRate === null ? '—' : f.percent(Number(r.savingsRate))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="mt-2 text-xs text-muted-foreground">مبالغ به {unitLabel(IRT, f.prefs)}</p>
          </div>
        )}
        {!isPending && !empty ? <Averages rows={rows} /> : null}
      </CardContent>
    </Card>
  )
}
