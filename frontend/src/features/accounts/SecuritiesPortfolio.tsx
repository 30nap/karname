import { AlertTriangle } from 'lucide-react'
import { Link } from 'react-router'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Tooltip } from '@/components/ui/tooltip'
import { Amount } from '@/components/finance/Amount'
import { useFormat } from '@/app/preferences'
import type { PortfolioHolding } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { useSecuritiesPortfolio } from './api'

/** Gain or loss as a share of what was paid; null when either is missing or nothing was paid. */
function gainRatio(gain: string | null, cost: string | null): number | null {
  if (gain === null || cost === null || Number(cost) <= 0) return null
  return Number(gain) / Number(cost)
}

function Gain({ gain, cost, className }: { gain: string | null; cost: string | null; className?: string }) {
  const f = useFormat()
  const ratio = gainRatio(gain, cost)
  return (
    <span className={cn('flex flex-wrap items-baseline gap-x-1.5', className)}>
      <Amount value={gain} tone="auto" />
      {ratio !== null ? (
        <bdi dir="rtl" className={cn('text-xs', ratio < 0 ? 'text-expense' : 'text-income')}>
          ({f.percent(ratio, 1)})
        </bdi>
      ) : null}
    </span>
  )
}

function HoldingRow({ holding: h }: { holding: PortfolioHolding }) {
  const f = useFormat()
  return (
    <li>
      <Link to={`/accounts/${h.accountId}`}
        className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1 rounded-lg p-2 transition-colors hover:bg-accent/50">
        <span className="min-w-0">
          <span className="block truncate font-medium">{h.symbol ?? h.nameFa}</span>
          <span className="block truncate text-xs text-muted-foreground">
            {f.number(h.quantity)} {h.symbol ? h.nameFa : 'واحد'}
            {h.averageCostToman ? <> · میانگین خرید <Amount value={h.averageCostToman} /></> : null}
          </span>
        </span>
        <span className="flex flex-col items-end text-sm">
          {h.marketValueToman ? (
            <span className="flex items-center gap-1 font-semibold">
              {h.priceStale ? (
                <Tooltip content="قیمت این نماد قدیمی است؛ از صفحه‌ی دارایی‌ها به‌روزش کنید.">
                  <AlertTriangle className="size-3.5 text-warning" aria-label="قیمت قدیمی" />
                </Tooltip>
              ) : null}
              <Amount value={h.marketValueToman} />
            </span>
          ) : (
            <span className="text-xs text-warning">قیمت ثبت نشده</span>
          )}
          {h.unrealizedToman !== null ? (
            <Gain gain={h.unrealizedToman} cost={h.costComplete ? h.costBasisToman : null} className="justify-end text-xs" />
          ) : null}
        </span>
      </Link>
    </li>
  )
}

/** The shares and funds across accounts: what they cost, what they are worth now, and the gain. */
export function SecuritiesPortfolio() {
  const f = useFormat()
  const { data } = useSecuritiesPortfolio()
  if (!data || data.holdings.length === 0) return null
  const held = data.holdings.filter((h) => Number(h.quantity) > 0)
  return (
    <Card aria-labelledby="portfolio-title">
      <CardHeader>
        <CardTitle id="portfolio-title">سبد بورس</CardTitle>
        <CardDescription>
          {f.number(held.length)} نماد
          {data.unpriced > 0 ? ` · ${f.number(data.unpriced)} نماد بدون قیمت در جمع نیامده` : ''}
          {!data.costComplete ? ' · بهای بخشی از خریدها معلوم نیست' : ''}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <div>
            <p className="text-xs text-muted-foreground">ارزش روز</p>
            <Amount value={data.marketValueToman} className="font-bold" />
          </div>
          <div>
            <p className="text-xs text-muted-foreground">بهای تمام‌شده</p>
            <Amount value={data.costBasisToman} className="font-bold" />
          </div>
          <div>
            <p className="text-xs text-muted-foreground">سود/زیان باز</p>
            <Gain gain={data.unrealizedToman} cost={data.costComplete ? data.costBasisToman : null} className="font-bold" />
          </div>
          <div>
            <p className="text-xs text-muted-foreground">سود/زیان فروش‌ها</p>
            <Amount value={data.realizedToman} tone="auto" className="font-bold" />
          </div>
        </div>
        {held.length > 0 ? (
          <ul className="-mx-2 grid gap-1 md:grid-cols-2">
            {held.map((h) => <HoldingRow key={h.accountId} holding={h} />)}
          </ul>
        ) : null}
      </CardContent>
    </Card>
  )
}
