import { daysBetween, formatJalaliLong, todayIso } from '../jalali'
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

/** «امروز», «فردا», «۳ روز دیگر», «۵ روز پیش» for a day offset from today. */
export function formatDayOffset(days: number, digits: DigitStyle = 'PERSIAN'): string {
  if (days === 0) return 'امروز'
  if (days === 1) return 'فردا'
  if (days === -1) return 'دیروز'
  const n = formatNumber(Math.abs(days), { digits })
  return days > 0 ? `${n} روز دیگر` : `${n} روز پیش`
}

/** «همین حالا», «۵ دقیقه پیش», «۳ ساعت پیش», «دیروز», «۴ روز پیش», then the date. */
export function formatTimeAgo(instant: string, digits: DigitStyle = 'PERSIAN', now: Date = new Date()): string {
  const then = new Date(instant)
  const minutes = Math.floor((now.getTime() - then.getTime()) / 60_000)
  const n = (v: number) => formatNumber(v, { digits })
  if (minutes < 1) return 'همین حالا'
  if (minutes < 60) return `${n(minutes)} دقیقه پیش`
  if (minutes < 24 * 60) return `${n(Math.floor(minutes / 60))} ساعت پیش`
  const day = todayIso(then)
  const days = daysBetween(day, todayIso(now))
  if (days <= 1) return 'دیروز'
  if (days < 7) return `${n(days)} روز پیش`
  return formatJalaliLong(day, digits)
}
