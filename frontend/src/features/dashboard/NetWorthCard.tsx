import { AlertTriangle } from 'lucide-react'
import { Link } from 'react-router'
import { Alert } from '@/components/ui/alert'
import { Card, CardContent } from '@/components/ui/card'
import { Tooltip } from '@/components/ui/tooltip'
import { Amount } from '@/components/finance/Amount'
import { chartColor } from '@/components/charts/chart-colors'
import { useFormat } from '@/app/preferences'
import { useCommodityMap } from '@/features/commodities/api'
import type { Commodity, NetWorth } from '@/lib/api/types'
import { IRT, unitLabel } from '@/lib/format/money'
import { ASSET_CLASS_LABELS, ASSET_CLASS_SLOT } from '@/lib/labels'
import { cn } from '@/lib/cn'

const GENERIC_UNITS = new Set(['گرم', 'مثقال', 'عدد', 'سکه'])

/** «۵۶ گرم» needs the commodity name after it («طلای ۱۸ عیار»); «۱٬۲۰۰ دلار» does not. */
function measureSuffix(unitFa: string, nameFa: string): string | null {
  if (!GENERIC_UNITS.has(unitFa)) return null
  return nameFa.startsWith(unitFa) ? nameFa.slice(unitFa.length).trim() : nameFa
}

/** The dashboard's hero figure: shown in full when it fits, compact on narrow screens otherwise. */
function HeroFigure({ value }: { value: string }) {
  const f = useFormat()
  const full = f.money(value, IRT, { withUnit: false })
  const compact = f.money(value, IRT, { withUnit: false, compact: true })
  const unit = unitLabel(IRT, f.prefs)
  const long = full.length > 11
  return (
    <div>
      <p className="flex flex-wrap items-baseline gap-x-2" aria-label={`${full} ${unit}`}>
        <bdi dir="rtl" className={cn('text-5xl font-bold leading-[1.15] tracking-tight', value.startsWith('-') && 'text-expense')}>
          {long ? (
            <>
              <span className="sm:hidden">{compact}</span>
              <span className="hidden sm:inline">{full}</span>
            </>
          ) : full}
        </bdi>
        <span className="text-base text-muted-foreground">{unit}</span>
      </p>
      {long ? <p className="mt-1 text-sm text-muted-foreground sm:hidden"><bdi dir="rtl">{full} {unit}</bdi></p> : null}
    </div>
  )
}

/** Part-to-whole of positive assets by class: a 100% bar with surface gaps and a legend that doubles as its table. */
function Allocation({ slices }: { slices: NetWorth['allocation'] }) {
  const f = useFormat()
  const sorted = [...slices].sort((a, b) => Number(b.share) - Number(a.share))
  if (sorted.length === 0) return null
  return (
    <div className="grid gap-3">
      <div className="flex h-3 w-full gap-[2px] overflow-hidden rounded-[4px]" role="img" aria-label="ترکیب دارایی‌ها">
        {sorted.map((s) => (
          <Tooltip key={s.assetClass} content={`${ASSET_CLASS_LABELS[s.assetClass]}: ${f.percent(Number(s.share), 1)}`}>
            <div
              className="h-full min-w-[3px] transition-opacity hover:opacity-80"
              style={{ width: `${Number(s.share) * 100}%`, background: chartColor(ASSET_CLASS_SLOT[s.assetClass]) }}
            />
          </Tooltip>
        ))}
      </div>
      <ul className="grid gap-x-6 gap-y-1.5 text-sm sm:grid-cols-2">
        {sorted.map((s) => (
          <li key={s.assetClass} className="flex items-center gap-2">
            <span className="size-2.5 shrink-0 rounded-[3px]" style={{ background: chartColor(ASSET_CLASS_SLOT[s.assetClass]) }} aria-hidden />
            <span className="min-w-0 flex-1 truncate">{ASSET_CLASS_LABELS[s.assetClass]}</span>
            <span className="tabular w-12 text-end text-muted-foreground">{f.percent(Number(s.share), 1)}</span>
            <Amount value={s.valueToman} compact className="tabular w-28 text-end text-muted-foreground" />
          </li>
        ))}
      </ul>
    </div>
  )
}

export function NetWorthCard({ netWorth }: { netWorth: NetWorth }) {
  const f = useFormat()
  const commodities = useCommodityMap()
  // An approximate measure needs no cents: «≈ ۶٬۸۸۵ دلار», «≈ ۷۸٫۸۵ گرم».
  const approximate = (value: string, commodity: Commodity) => {
    const abs = Math.abs(Number(value))
    const maxFraction = abs >= 100 ? 0 : abs >= 1 ? Math.min(2, commodity.scale) : commodity.scale
    return `${f.number(value, { maxFraction })} ${unitLabel(commodity, f.prefs)}`
  }
  const hasLiabilities = Number(netWorth.liabilitiesToman) > 0
  return (
    <Card>
      <CardContent className="grid gap-5 p-5 sm:p-6">
        <div className="grid gap-2">
          <p className="text-sm font-medium text-muted-foreground">دارایی خالص</p>
          <HeroFigure value={netWorth.totalToman} />
          {netWorth.alternatives.length > 0 ? (
            <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground">
              {netWorth.alternatives.map((alt) => (
                <span key={alt.code} className="flex items-center gap-1">
                  ≈ <bdi dir="rtl" className="font-medium text-foreground">{approximate(alt.value, commodities.get(alt.code))}</bdi>
                  {measureSuffix(alt.unitFa, alt.nameFa)}
                  {alt.stale ? (
                    <Tooltip content={`قیمت ${alt.nameFa} قدیمی است؛ این عدد ممکن است دقیق نباشد.`}>
                      <AlertTriangle className="size-3.5 text-warning" aria-label="قیمت قدیمی" />
                    </Tooltip>
                  ) : null}
                </span>
              ))}
            </p>
          ) : null}
        </div>

        <div className="grid grid-cols-2 gap-4 border-t pt-4">
          <div>
            <p className="text-xs text-muted-foreground">دارایی‌ها</p>
            <Amount value={netWorth.assetsToman} className="text-lg font-semibold" />
          </div>
          <div>
            <p className="text-xs text-muted-foreground">بدهی‌ها</p>
            <Amount value={netWorth.liabilitiesToman} className={cn('text-lg font-semibold', !hasLiabilities && 'text-muted-foreground')} />
          </div>
        </div>

        <Allocation slices={netWorth.allocation} />

        {netWorth.unpriced.length > 0 ? (
          <Alert variant="warning">
            <AlertTriangle />
            <div>
              قیمت واحدِ {netWorth.unpriced.length === 1 ? 'این حساب' : `این ${f.number(netWorth.unpriced.length)} حساب`} ثبت نشده و ارزشش در جمع نیامده است:
              {' '}{netWorth.unpriced.map((u) => u.name).join('، ')}.{' '}
              <Link to="/assets" className="font-medium text-primary underline-offset-4 hover:underline">ثبت قیمت</Link>
            </div>
          </Alert>
        ) : null}
      </CardContent>
    </Card>
  )
}
