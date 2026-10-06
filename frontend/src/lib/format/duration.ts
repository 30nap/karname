import type { DigitStyle } from './number'
import { formatNumber } from './number'

/** «۸ ماه», «۲ سال», «۱۱ سال و ۹ ماه». */
export function formatMonths(months: number, digits: DigitStyle = 'PERSIAN'): string {
  const n = (v: number) => formatNumber(v, { digits })
  const years = Math.floor(months / 12)
  const rest = months % 12
  if (years === 0) return `${n(rest)} ماه`
  if (rest === 0) return `${n(years)} سال`
  return `${n(years)} سال و ${n(rest)} ماه`
}
