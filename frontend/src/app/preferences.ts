import { useMemo } from 'react'
import { useAuthStatus } from '@/features/auth/api'
import { DEFAULT_PREFS, formatMoney, type CommodityDisplay, type FormatMoneyOptions, type MoneyPrefs } from '@/lib/format/money'
import { formatCompact, formatNumber, formatPercent, type NumberFormatOptions } from '@/lib/format/number'
import { formatDateTime, formatJalaliLong, formatJalaliShort, monthLabel } from '@/lib/jalali'
import { toPersianDigits } from '@/lib/persian/digits'

export function usePrefs(): MoneyPrefs {
  const { data } = useAuthStatus()
  const settings = data?.user?.settings
  return useMemo(
    () => (settings ? { digits: settings.digitStyle, displayUnit: settings.displayUnit } : DEFAULT_PREFS),
    [settings],
  )
}

/** Formatters bound to the user's digit and currency-unit preferences. */
export function useFormat() {
  const prefs = usePrefs()
  return useMemo(
    () => ({
      prefs,
      money: (amount: string | null | undefined, commodity: CommodityDisplay, options?: FormatMoneyOptions) =>
        formatMoney(amount, commodity, prefs, options),
      number: (value: string | number, options?: NumberFormatOptions) => formatNumber(value, { digits: prefs.digits, ...options }),
      compact: (value: string | number) => formatCompact(value, prefs.digits),
      percent: (value: number, maxFraction = 0) => formatPercent(value, prefs.digits, maxFraction),
      date: (iso: string | null | undefined) => formatJalaliLong(iso, prefs.digits),
      dateShort: (iso: string | null | undefined) => formatJalaliShort(iso, prefs.digits),
      dateTime: (instant: string | null | undefined) => formatDateTime(instant, prefs.digits),
      month: (key: string) => monthLabel(key, prefs.digits),
      digits: (text: string | number) => (prefs.digits === 'PERSIAN' ? toPersianDigits(String(text)) : String(text)),
    }),
    [prefs],
  )
}
