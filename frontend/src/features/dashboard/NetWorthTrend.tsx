import { useMemo, useState } from 'react'
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { AXIS_TICK, chartColor } from '@/components/charts/chart-colors'
import { ChartTooltipBox, ChartViewToggle, type ChartView } from '@/components/charts/chart-utils'
import { useFormat } from '@/app/preferences'
import { useCommodityMap } from '@/features/commodities/api'
import { useNetWorthHistory } from '@/features/transactions/api'
import { toDisplayAmount } from '@/lib/format/money'
import { JALALI_MONTHS, parseMonthKey } from '@/lib/jalali'

/** Short names for the measure switch; it must fit beside the card title. */
const MEASURE_LABELS: Record<string, string> = {
  IRT: 'تومان', USD: 'دلار', EUR: 'یورو', USDT: 'تتر', GOLD18: 'طلا', GOLD24: 'طلای ۲۴', COIN_EMAMI: 'سکه', BTC: 'بیت‌کوین',
}

interface Row {
  month: string
  /** Exact value as returned by the API (Toman or units of the measure). */
  value: string | null
  /** Display-unit number used only for plotting. */
  plot: number | null
}

/** Net worth at the end of each Jalali month, measured in Toman or in an alternative unit (USD, gold…). */
export function NetWorthTrend() {
  const f = useFormat()
  const commodities = useCommodityMap()
  const { data, isPending } = useNetWorthHistory(12)
  const [measure, setMeasure] = useState('IRT')
  const [view, setView] = useState<ChartView>('chart')

  const measures = useMemo(() => {
    const codes = new Set<string>()
    data?.forEach((p) => Object.keys(p.alternatives).forEach((c) => codes.add(c)))
    return ['IRT', ...codes]
  }, [data])
  const active = measures.includes(measure) ? measure : 'IRT'
  const commodity = commodities.get(active)

  const rows: Row[] = useMemo(
    () =>
      (data ?? []).map((p) => {
        const value = active === 'IRT' ? p.totalToman : (p.alternatives[active] ?? null)
        return { month: p.month, value, plot: value === null ? null : Number(toDisplayAmount(value, active, f.prefs)) }
      }),
    [data, active, f.prefs],
  )
  const hasData = rows.some((r) => r.plot !== null && r.plot !== 0)
  const monthName = (key: string) => JALALI_MONTHS[parseMonthKey(key).month - 1]
  const format = (value: string | null) => f.money(value, commodity)

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>روند دارایی خالص</CardTitle>
        <div className="flex items-center gap-2">
          {measures.length > 1 ? (
            <Segmented
              ariaLabel="واحد سنجش"
              size="sm"
              value={active}
              onChange={setMeasure}
              options={measures.map((code) => ({ value: code, label: MEASURE_LABELS[code] ?? commodities.get(code).nameFa }))}
            />
          ) : null}
          <ChartViewToggle value={view} onChange={setView} />
        </div>
      </CardHeader>
      <CardContent>
        {isPending ? (
          <Skeleton className="h-56" />
        ) : !hasData ? (
          <p className="flex h-40 items-center justify-center text-center text-sm text-muted-foreground">
            با ثبت حساب‌ها و تراکنش‌ها، روند دارایی خالص شما در ۱۲ ماه گذشته این‌جا نمایش داده می‌شود.
          </p>
        ) : view === 'chart' ? (
          <div dir="ltr" className="h-56 w-full" role="img" aria-label={`نمودار روند دارایی خالص به ${commodity.unitFa}`}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={rows} margin={{ top: 8, right: 4, left: 4, bottom: 0 }}>
                <defs>
                  <linearGradient id="netWorthFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={chartColor(1)} stopOpacity={0.18} />
                    <stop offset="100%" stopColor={chartColor(1)} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid vertical={false} stroke="var(--chart-grid)" />
                <XAxis dataKey="month" tickFormatter={monthName} tick={AXIS_TICK} tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={12} />
                <YAxis
                  orientation="right"
                  width={64}
                  tick={AXIS_TICK}
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v: number) => f.compact(v)}
                  domain={['auto', 'auto']}
                />
                <Tooltip
                  cursor={{ stroke: 'var(--color-muted-foreground)', strokeWidth: 1, strokeDasharray: '3 3' }}
                  content={({ active: on, payload }) => {
                    const row = on && payload?.length ? (payload[0].payload as Row) : null
                    if (!row) return null
                    return (
                      <ChartTooltipBox title={f.month(row.month)}>
                        <bdi dir="rtl" className="font-semibold">{format(row.value)}</bdi>
                      </ChartTooltipBox>
                    )
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="plot"
                  stroke={chartColor(1)}
                  strokeWidth={2}
                  fill="url(#netWorthFill)"
                  connectNulls={false}
                  dot={false}
                  activeDot={{ r: 5, strokeWidth: 2, stroke: 'var(--color-card)', fill: chartColor(1) }}
                  isAnimationActive={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-card text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 text-start font-medium">پایان ماه</th>
                  <th className="py-2 text-end font-medium">دارایی خالص</th>
                </tr>
              </thead>
              <tbody className="tabular">
                {[...rows].reverse().map((r) => (
                  <tr key={r.month} className="border-t">
                    <td className="py-2">{f.month(r.month)}</td>
                    <td className="py-2 text-end"><bdi dir="rtl">{format(r.value)}</bdi></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
