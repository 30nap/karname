import { useFormat } from '@/app/preferences'
import { useCommodityMap } from '@/features/commodities/api'
import { cn } from '@/lib/cn'

type Tone = 'neutral' | 'income' | 'expense' | 'auto'

/** A money amount in its commodity, formatted for the user's preferences. */
export function Amount({ value, commodity = 'IRT', tone = 'neutral', sign, compact, rate, className, withUnit = true }: {
  value: string | null | undefined
  commodity?: string
  tone?: Tone
  /** Show a leading + or − (for flows). */
  sign?: '+' | '-'
  compact?: boolean
  /** A rate per month: whole units (coins, shares) get two decimals. */
  rate?: boolean
  withUnit?: boolean
  className?: string
}) {
  const f = useFormat()
  const commodities = useCommodityMap()
  if (value === null || value === undefined) return <span className={cn('text-muted-foreground', className)}>—</span>
  const negative = value.startsWith('-')
  const resolved = tone === 'auto' ? (negative ? 'expense' : 'income') : tone
  const abs = negative ? value.slice(1) : value
  // Intl places the sign the Persian (CLDR) way: an LRM-isolated minus on the left of the digits.
  const display = sign === '-' ? `-${abs}` : sign === '+' ? abs : value
  const text = f.money(display, commodities.get(commodity), { compact, withUnit, rate, signDisplay: sign === '+' ? 'always' : 'auto' })
  return (
    <bdi dir="rtl" className={cn('whitespace-nowrap', resolved === 'income' && 'text-income', resolved === 'expense' && 'text-expense', className)}>
      {text}
    </bdi>
  )
}
