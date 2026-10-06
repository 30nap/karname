import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { chartColor } from '@/components/charts/chart-colors'
import { useFormat } from '@/app/preferences'
import { useBudgetMonth } from '@/features/budgets/api'
import { useGoals } from '@/features/goals/api'
import { useCategoryReport } from '@/features/reports/api'
import { currentMonthKey } from '@/lib/jalali'

function WidgetCard({ title, to, children }: { title: string; to: string; children: ReactNode }) {
  return (
    <Card className="min-w-0">
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle>{title}</CardTitle>
        <Button asChild variant="ghost" size="sm"><Link to={to}>همه<ChevronLeft /></Link></Button>
      </CardHeader>
      <CardContent className="pt-0">{children}</CardContent>
    </Card>
  )
}

function Empty({ text, action, to }: { text: string; action: string; to: string }) {
  return (
    <div className="flex flex-col items-start gap-2 py-2">
      <p className="text-sm text-muted-foreground">{text}</p>
      <Button asChild variant="outline" size="sm"><Link to={to}>{action}</Link></Button>
    </div>
  )
}

/** This month's top expense categories as ranked bars in the expense hue. */
export function TopCategoriesWidget() {
  const f = useFormat()
  const month = currentMonthKey()
  const { data, isPending } = useCategoryReport(month, 1, 'EXPENSE')
  const items = (data?.items ?? []).filter((i) => Number(i.valueToman) > 0).slice(0, 5)
  const max = Math.max(1, ...items.map((i) => Number(i.valueToman)))
  return (
    <WidgetCard title="هزینه‌ها به تفکیک دسته" to="/reports?tab=categories">
      {isPending ? <Skeleton className="h-40" /> : items.length === 0 ? (
        <p className="py-2 text-sm text-muted-foreground">این ماه هنوز هزینه‌ای ثبت نشده است.</p>
      ) : (
        <ul className="grid gap-3">
          {items.map((i) => (
            <li key={i.categoryId ?? 'none'} className="grid gap-1">
              <div className="flex items-center gap-2 text-sm">
                <CategoryIcon name={i.icon} className="text-muted-foreground" />
                <span className="min-w-0 flex-1 truncate">{i.name ?? 'بدون دسته‌بندی'}</span>
                <span className="text-xs text-muted-foreground tabular">{f.percent(Number(i.share))}</span>
                <Amount value={i.valueToman} compact className="text-sm font-medium" />
              </div>
              <div className="h-1.5 overflow-hidden rounded-[4px] bg-muted">
                <div className="h-full rounded-[4px]" style={{ width: `${(Number(i.valueToman) / max) * 100}%`, background: chartColor(2) }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </WidgetCard>
  )
}

/** Budgets closest to (or over) their limit this month. */
export function BudgetWidget() {
  const f = useFormat()
  const { data, isPending } = useBudgetMonth(currentMonthKey())
  const items = [...(data?.items ?? [])].sort((a, b) => Number(b.ratio) - Number(a.ratio)).slice(0, 4)
  return (
    <WidgetCard title="بودجه‌ی این ماه" to="/budgets">
      {isPending ? <Skeleton className="h-40" /> : items.length === 0 ? (
        <Empty text="برای دسته‌های پرهزینه سقف ماهانه بگذارید." action="تعیین بودجه" to="/budgets" />
      ) : (
        <ul className="grid gap-3">
          {items.map((i) => (
            <li key={i.categoryId} className="grid gap-1">
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <span className="truncate">{i.name}</span>
                <span className="shrink-0 text-xs text-muted-foreground"><Amount value={i.spent} compact /> از <Amount value={i.amount} compact /></span>
              </div>
              <Progress value={Number(i.ratio)} tone="auto" label={`${i.name}: ${f.percent(Number(i.ratio))}`} />
            </li>
          ))}
        </ul>
      )}
    </WidgetCard>
  )
}

export function GoalsWidget() {
  const f = useFormat()
  const { data, isPending } = useGoals()
  const goals = (data ?? []).slice(0, 3)
  return (
    <WidgetCard title="اهداف" to="/goals">
      {isPending ? <Skeleton className="h-40" /> : goals.length === 0 ? (
        <Empty text="برای پس‌انداز هدف بگذارید؛ مثلاً صندوق سفر یا مهاجرت." action="تعریف هدف" to="/goals" />
      ) : (
        <ul className="grid gap-3">
          {goals.map((g) => (
            <li key={g.id} className="grid gap-1">
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <span className="flex min-w-0 items-center gap-1.5"><CategoryIcon name={g.icon ?? 'flag'} className="shrink-0 text-muted-foreground" /><span className="truncate">{g.name}</span></span>
                <span className="shrink-0 text-xs text-muted-foreground">{g.progress === null ? '—' : f.percent(Number(g.progress))}</span>
              </div>
              <Progress value={g.progress === null ? 0 : Number(g.progress)} tone={g.achieved ? 'income' : 'primary'} label={g.name} />
            </li>
          ))}
        </ul>
      )}
    </WidgetCard>
  )
}
