import { AlertTriangle, ArrowLeftRight, ChevronLeft, FileText, Landmark, Repeat } from 'lucide-react'
import Big from 'big.js'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { AXIS_TICK, chartColor } from '@/components/charts/chart-colors'
import { ChartTooltipBox, ChartViewToggle, TooltipRow, type ChartView } from '@/components/charts/chart-utils'
import { useFormat } from '@/app/preferences'
import type { ForecastEvent, ForecastSource } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatDayOffset } from '@/lib/format/duration'
import { toDisplayAmount } from '@/lib/format/money'
import { addDaysIso, daysBetween, formatJalaliWithWeekday, JALALI_MONTHS, toJalali, todayIso } from '@/lib/jalali'
import { FORECAST_SOURCE_LABELS } from '@/lib/labels'
import { useForecast } from './api'

const HORIZONS = ['30', '60', '90'] as const
type Horizon = (typeof HORIZONS)[number]
const LINE = chartColor(1)
/** Events listed before «نمایش همه»; a weekly rule alone adds thirteen in ninety days. */
const EVENTS_PAGE = 20

const SOURCE_ICONS: Record<ForecastSource, typeof Repeat> = { RECORDED: ArrowLeftRight, RECURRING: Repeat, LOAN: Landmark, CHEQUE: FileText }

interface Row {
  x: number
  date: string
  balance: string
  plot: number
}

function groupByDate(events: ForecastEvent[]) {
  const map = new Map<string, ForecastEvent[]>()
  events.forEach((e) => map.set(e.date, [...(map.get(e.date) ?? []), e]))
  return map
}

/** Four or five dates along the axis, starting today, so labels fit a phone screen. */
function ticksFor(days: number): number[] {
  const step = days <= 30 ? 7 : days <= 60 ? 15 : 30
  return Array.from({ length: Math.floor(days / step) + 1 }, (_, i) => i * step)
}

export function EventRow({ event, compact }: { event: ForecastEvent; compact?: boolean }) {
  const f = useFormat()
  const Icon = SOURCE_ICONS[event.source]
  const inflow = !event.amount.startsWith('-')
  return (
    <Link to={event.link} className="flex items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-accent/60">
      <span className={cn('flex size-9 shrink-0 items-center justify-center rounded-full', inflow ? 'bg-income/10 text-income' : 'bg-expense/10 text-expense')}>
        <Icon className="size-4" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className={cn('text-sm font-medium', compact && 'truncate')}>{event.title}</span>
          {event.overdue ? <Badge variant="warning" className="shrink-0">عقب‌افتاده</Badge> : null}
        </span>
        <span className="block truncate text-xs text-muted-foreground">
          {compact ? `${f.dateShort(event.date)} | ` : ''}{FORECAST_SOURCE_LABELS[event.source]}
        </span>
      </span>
      <Amount value={event.amount.replace(/^-/, '')} tone={inflow ? 'income' : 'expense'} sign={inflow ? '+' : '-'} className="text-sm font-semibold" />
    </Link>
  )
}

/** Projected Toman cash (cash, bank and e-wallet accounts) from known obligations and income. */
export function ForecastPanel() {
  const f = useFormat()
  const [horizon, setHorizon] = useState<Horizon>('90')
  const [view, setView] = useState<ChartView>('chart')
  const [allEvents, setAllEvents] = useState(false)
  const { data, isPending, isPlaceholderData } = useForecast(Number(horizon))
  const days = Number(horizon)

  const rows: Row[] = useMemo(
    () => (data?.points ?? []).map((p) => ({
      x: daysBetween(data!.from, p.date),
      date: p.date,
      balance: p.balance,
      plot: Number(toDisplayAmount(p.balance, 'IRT', f.prefs)),
    })),
    [data, f.prefs],
  )
  const eventsByDate = useMemo(() => groupByDate(data?.events ?? []), [data])
  const listed = useMemo(() => groupByDate((data?.events ?? []).slice(0, allEvents ? undefined : EVENTS_PAGE)), [data, allEvents])
  const tickLabel = (x: number) => {
    if (!data) return ''
    const { month, day } = toJalali(addDaysIso(data.from, x))
    return `${f.digits(day)} ${JALALI_MONTHS[month - 1]}`
  }
  const today = todayIso()
  const negative = data ? data.minBalance.startsWith('-') : false

  return (
    <div className={cn('grid grid-cols-1 gap-4', isPlaceholderData && 'opacity-60 transition-opacity')}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">موجودی حساب‌های نقدی، بانکی و کیف پول تومانی، با درآمدها و پرداخت‌های شناخته‌شده</p>
        <Segmented<Horizon> ariaLabel="بازه‌ی پیش‌بینی" size="sm" value={horizon} onChange={setHorizon}
          options={HORIZONS.map((h) => ({ value: h, label: `${f.number(Number(h))} روز` }))} />
      </div>

      {isPending || !data ? <Skeleton className="h-96" /> : (
        <>
          {negative ? (
            <Alert variant="destructive">
              <AlertTriangle />
              <span>
                پیش‌بینی می‌شود موجودی نقد شما در {f.date(data.minDate)} به <Amount value={data.minBalance} className="font-semibold" /> برسد.
                برای پوشش این کسری از حالا برنامه بریزید؛ مثلاً انتقال از پس‌انداز یا جابه‌جایی یک پرداخت.
              </span>
            </Alert>
          ) : null}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Card>
              <CardContent className="p-4">
                <p className="text-xs text-muted-foreground">موجودی نقد امروز</p>
                <Amount value={data.startBalance} className="text-xl font-bold" />
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <p className="text-xs text-muted-foreground">کمترین موجودی</p>
                <Amount value={data.minBalance} tone={negative ? 'expense' : 'neutral'} className="text-xl font-bold" />
                <p className="text-xs text-muted-foreground">{data.minDate === today ? 'امروز' : f.date(data.minDate)}</p>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <p className="text-xs text-muted-foreground">ورودی و خروجی پیش رو</p>
                <p className="flex flex-wrap gap-x-2 text-sm font-semibold">
                  <Amount value={data.inflow} tone="income" sign="+" />
                  <Amount value={data.outflow} tone="expense" sign="-" />
                </p>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <p className="text-xs text-muted-foreground">پیش‌بینی پایان دوره</p>
                <Amount value={data.endBalance} tone={data.endBalance.startsWith('-') ? 'expense' : 'neutral'} className="text-xl font-bold" />
                <p className="text-xs text-muted-foreground">{f.date(data.to)}</p>
              </CardContent>
            </Card>
          </div>

          <Card>
            <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
              <CardTitle>موجودی نقد در {f.number(days)} روز آینده</CardTitle>
              <ChartViewToggle value={view} onChange={setView} />
            </CardHeader>
            <CardContent>
              {view === 'chart' ? (
                <div dir="ltr" className="h-72 w-full" role="img" aria-label={`نمودار پیش‌بینی موجودی نقد در ${f.number(days)} روز آینده`}>
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={rows} margin={{ top: 8, right: 4, left: 20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="forecastFill" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor={LINE} stopOpacity={0.16} />
                          <stop offset="100%" stopColor={LINE} stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid vertical={false} stroke="var(--chart-grid)" />
                      <XAxis type="number" dataKey="x" domain={[0, days]} ticks={ticksFor(days)} interval={0} tickFormatter={tickLabel}
                        tick={AXIS_TICK} tickLine={false} axisLine={false} />
                      {/* Zero stays in view: how close cash gets to running out is the point of this chart. */}
                      <YAxis orientation="right" width={64} tick={AXIS_TICK} tickLine={false} axisLine={false}
                        tickFormatter={(v: number) => f.compact(v)} domain={[(min: number) => Math.min(0, min), 'auto']} />
                      <ReferenceLine y={0} stroke={negative ? 'var(--color-destructive)' : 'var(--color-border)'} strokeDasharray={negative ? '4 3' : undefined} />
                      <Tooltip
                        cursor={{ stroke: 'var(--color-muted-foreground)', strokeWidth: 1, strokeDasharray: '3 3' }}
                        content={({ active, payload }) => {
                          const row = active && payload?.length ? (payload[0].payload as Row) : null
                          if (!row) return null
                          const events = eventsByDate.get(row.date) ?? []
                          return (
                            <ChartTooltipBox title={formatJalaliWithWeekday(row.date, f.prefs.digits)}>
                              <TooltipRow color={LINE} label="موجودی" value={<Amount value={row.balance} />} />
                              {events.slice(0, 4).map((e, i) => (
                                <TooltipRow key={i} label={<span className="max-w-40 truncate">{e.title}</span>}
                                  value={<Amount value={e.amount.replace(/^-/, '')} sign={e.amount.startsWith('-') ? '-' : '+'} />} />
                              ))}
                              {events.length > 4 ? <p className="text-muted-foreground">و {f.number(events.length - 4)} مورد دیگر</p> : null}
                            </ChartTooltipBox>
                          )
                        }}
                      />
                      <Area type="stepAfter" dataKey="plot" stroke={LINE} strokeWidth={2} fill="url(#forecastFill)" baseValue={0}
                        dot={false} activeDot={{ r: 5, strokeWidth: 2, stroke: 'var(--color-card)', fill: LINE }} isAnimationActive={false} />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="max-h-80 overflow-y-auto">
                  <table className="w-full text-sm">
                    <thead className="sticky top-0 bg-card text-xs text-muted-foreground">
                      <tr>
                        <th className="py-2 text-start font-medium">تاریخ</th>
                        <th className="py-2 text-end font-medium">تغییر</th>
                        <th className="py-2 text-end font-medium">موجودی پس از آن</th>
                      </tr>
                    </thead>
                    <tbody className="tabular">
                      {rows.map((r, i) => {
                        const delta = new Big(r.balance).minus(i === 0 ? data.startBalance : rows[i - 1].balance)
                        return (
                          <tr key={r.date} className="border-t">
                            <td className="py-2">{f.date(r.date)}</td>
                            <td className="py-2 text-end">{delta.eq(0) ? '—' : <Amount value={delta.toFixed()} tone="auto" withUnit={false} />}</td>
                            <td className="py-2 text-end"><Amount value={r.balance} /></td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>رویدادهای پیش رو</CardTitle>
              <CardDescription>موارد عقب‌افتاده (سررسید گذشته و ثبت‌نشده) در امروز حساب شده‌اند.</CardDescription>
            </CardHeader>
            <CardContent className="pt-0">
              {data.events.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  در این بازه درآمد یا پرداخت شناخته‌شده‌ای نیست. با تعریف <Link to="/recurring" className="font-medium text-primary hover:underline">تراکنش‌های تکراری</Link>، وام‌ها و چک‌ها پیش‌بینی دقیق‌تر می‌شود.
                </p>
              ) : (
                <div className="grid grid-cols-1 gap-3">
                  {[...listed.entries()].map(([date, events]) => (
                    <section key={date} aria-label={f.date(date)}>
                      <h3 className="px-2 pb-1 text-xs font-semibold text-muted-foreground">
                        {formatJalaliWithWeekday(date, f.prefs.digits)} | {formatDayOffset(daysBetween(today, date), f.prefs.digits)}
                      </h3>
                      {events.map((e, i) => <EventRow key={i} event={e} />)}
                    </section>
                  ))}
                  {data.events.length > EVENTS_PAGE && !allEvents ? (
                    <div className="flex justify-center">
                      <Button variant="outline" onClick={() => setAllEvents(true)}>نمایش {f.number(data.events.length - EVENTS_PAGE)} رویداد دیگر</Button>
                    </div>
                  ) : null}
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

/** Dashboard summary: next month's cash low point and the obligations coming up. */
export function UpcomingWidget() {
  const { data, isPending } = useForecast(30)
  const upcoming = (data?.events ?? []).slice(0, 5)
  const negative = data?.minBalance.startsWith('-') ?? false
  return (
    <Card className="min-w-0">
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle>۳۰ روز آینده</CardTitle>
        <Button asChild variant="ghost" size="sm"><Link to="/reports?tab=forecast">پیش‌بینی<ChevronLeft /></Link></Button>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-3 pt-0">
        {isPending || !data ? <Skeleton className="h-40" /> : (
          <>
            <dl className="grid grid-cols-2 gap-2 rounded-xl bg-muted/50 p-3 text-sm">
              <div>
                <dt className="text-xs text-muted-foreground">موجودی نقد امروز</dt>
                <dd><Amount value={data.startBalance} className="font-semibold" /></dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">کمترین موجودی پیش رو</dt>
                <dd><Amount value={data.minBalance} tone={negative ? 'expense' : 'neutral'} className="font-semibold" /></dd>
              </div>
            </dl>
            {upcoming.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                قسط، چک یا تراکنش تکراری‌ای در ۳۰ روز آینده نیست. <Link to="/recurring" className="font-medium text-primary hover:underline">تعریف تراکنش تکراری</Link>
              </p>
            ) : (
              <div className="-mx-2">{upcoming.map((e, i) => <EventRow key={i} event={e} compact />)}</div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
