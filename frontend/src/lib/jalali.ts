import { addMonths, getDate, getDaysInMonth, getMonth, getYear, newDate } from 'date-fns-jalali'
import { toPersianDigits } from './persian/digits'
import type { DigitStyle } from './format/number'

export const JALALI_MONTHS = [
  'فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور',
  'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند',
] as const

/** Week starts on Saturday in Iran. */
export const WEEKDAYS_SHORT = ['ش', 'ی', 'د', 'س', 'چ', 'پ', 'ج'] as const
export const WEEKDAYS = ['شنبه', 'یکشنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنجشنبه', 'جمعه'] as const

export const APP_TIME_ZONE = 'Asia/Tehran'

export interface JalaliParts {
  year: number
  month: number // 1-12
  day: number
}

/** Parses 'YYYY-MM-DD' (Gregorian, as the API sends it) into a local-midnight Date. */
export function isoToDate(iso: string): Date {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function dateToIso(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** Today's date in Tehran as 'YYYY-MM-DD', independent of the device time zone. */
export function todayIso(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: APP_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const get = (t: string) => parts.find((p) => p.type === t)!.value
  return `${get('year')}-${get('month')}-${get('day')}`
}

export function toJalali(iso: string): JalaliParts {
  const date = isoToDate(iso)
  return { year: getYear(date), month: getMonth(date) + 1, day: getDate(date) }
}

export function fromJalali(year: number, month: number, day: number): string {
  return dateToIso(newDate(year, month - 1, day))
}

export function jalaliMonthLength(year: number, month: number): number {
  return getDaysInMonth(newDate(year, month - 1, 1))
}

/** Day of week of a Jalali date with Saturday = 0. */
export function jalaliWeekdayIndex(iso: string): number {
  return (isoToDate(iso).getDay() + 1) % 7
}

function digitsOf(text: string, digits: DigitStyle) {
  return digits === 'PERSIAN' ? toPersianDigits(text) : text
}

/** «۱۴۰۵/۰۷/۱۴» */
export function formatJalaliShort(iso: string | null | undefined, digits: DigitStyle = 'PERSIAN'): string {
  if (!iso) return '—'
  const { year, month, day } = toJalali(iso)
  return digitsOf(`${year}/${String(month).padStart(2, '0')}/${String(day).padStart(2, '0')}`, digits)
}

/** «۱۴ مهر ۱۴۰۵» */
export function formatJalaliLong(iso: string | null | undefined, digits: DigitStyle = 'PERSIAN'): string {
  if (!iso) return '—'
  const { year, month, day } = toJalali(iso)
  return digitsOf(`${day} ${JALALI_MONTHS[month - 1]} ${year}`, digits)
}

/** «سه‌شنبه ۱۴ مهر» */
export function formatJalaliWithWeekday(iso: string, digits: DigitStyle = 'PERSIAN'): string {
  const { month, day } = toJalali(iso)
  return digitsOf(`${WEEKDAYS[jalaliWeekdayIndex(iso)]} ${day} ${JALALI_MONTHS[month - 1]}`, digits)
}

/** Formats an ISO timestamp in Tehran time: «۱۴ مهر ۱۴۰۵، ۱۲:۳۰». */
export function formatDateTime(instant: string | null | undefined, digits: DigitStyle = 'PERSIAN'): string {
  if (!instant) return '—'
  const locale = digits === 'PERSIAN' ? 'fa-IR-u-ca-persian' : 'fa-IR-u-ca-persian-nu-latn'
  return new Intl.DateTimeFormat(locale, {
    timeZone: APP_TIME_ZONE, year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(instant))
}

/** Jalali month key used by the API: '1405-07'. */
export type MonthKey = string

export function monthKey(year: number, month: number): MonthKey {
  return `${year}-${String(month).padStart(2, '0')}`
}

export function parseMonthKey(key: MonthKey): { year: number; month: number } {
  const [year, month] = key.split('-').map(Number)
  return { year, month }
}

export function currentMonthKey(today: string = todayIso()): MonthKey {
  const { year, month } = toJalali(today)
  return monthKey(year, month)
}

export function addMonthsToKey(key: MonthKey, months: number): MonthKey {
  const { year, month } = parseMonthKey(key)
  const d = addMonths(newDate(year, month - 1, 1), months)
  return monthKey(getYear(d), getMonth(d) + 1)
}

/** «مهر ۱۴۰۵» */
export function monthLabel(key: MonthKey, digits: DigitStyle = 'PERSIAN'): string {
  const { year, month } = parseMonthKey(key)
  return digitsOf(`${JALALI_MONTHS[month - 1]} ${year}`, digits)
}

export function monthRange(key: MonthKey): { start: string; end: string } {
  const { year, month } = parseMonthKey(key)
  return { start: fromJalali(year, month, 1), end: fromJalali(year, month, jalaliMonthLength(year, month)) }
}

export function addDaysIso(iso: string, days: number): string {
  const d = isoToDate(iso)
  d.setDate(d.getDate() + days)
  return dateToIso(d)
}

/** Whole days from {@code from} to {@code to} (negative when {@code to} is earlier). */
export function daysBetween(from: string, to: string): number {
  const utc = (iso: string) => {
    const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
    return Date.UTC(y, m - 1, d)
  }
  return Math.round((utc(to) - utc(from)) / 86_400_000)
}
