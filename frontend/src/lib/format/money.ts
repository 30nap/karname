import Big from 'big.js'
import { formatCompact, formatNumber, type DigitStyle } from './number'

export type DisplayUnit = 'TOMAN' | 'RIAL'

export interface MoneyPrefs {
  digits: DigitStyle
  displayUnit: DisplayUnit
}

export const DEFAULT_PREFS: MoneyPrefs = { digits: 'PERSIAN', displayUnit: 'TOMAN' }

/** Minimal commodity info needed for display; the full list comes from the API. */
export interface CommodityDisplay {
  code: string
  unitFa: string
  scale: number
}

export const IRT: CommodityDisplay = { code: 'IRT', unitFa: 'تومان', scale: 0 }

/** Converts a stored Toman amount to the user's display unit (Rial = Toman × 10). */
export function toDisplayAmount(amount: string, commodity: string, prefs: MoneyPrefs): string {
  if (commodity === 'IRT' && prefs.displayUnit === 'RIAL') {
    return new Big(amount).times(10).toFixed()
  }
  return amount
}

/** Converts what the user typed in their display unit back to Toman for the API. */
export function fromDisplayAmount(amount: string, commodity: string, prefs: MoneyPrefs): string {
  if (commodity === 'IRT' && prefs.displayUnit === 'RIAL') {
    return new Big(amount).div(10).toFixed()
  }
  return amount
}

export function unitLabel(commodity: CommodityDisplay, prefs: MoneyPrefs): string {
  if (commodity.code === 'IRT') return prefs.displayUnit === 'RIAL' ? 'ریال' : 'تومان'
  return commodity.unitFa
}

export interface FormatMoneyOptions {
  withUnit?: boolean
  signDisplay?: Intl.NumberFormatOptions['signDisplay']
  compact?: boolean
  /** A rate per month: units counted whole (coins, shares) get two decimals, so 0.17 coins a month is not shown as 0. */
  rate?: boolean
}

export function formatMoney(amount: string | null | undefined, commodity: CommodityDisplay, prefs: MoneyPrefs,
  options: FormatMoneyOptions = {}): string {
  if (amount === null || amount === undefined || amount === '') return '—'
  const display = toDisplayAmount(amount, commodity.code, prefs)
  const scale = commodity.code === 'IRT' ? 0 : options.rate ? Math.max(commodity.scale, 2) : commodity.scale
  const text = options.compact
    ? formatCompact(display, prefs.digits)
    : formatNumber(display, { digits: prefs.digits, maxFraction: scale, signDisplay: options.signDisplay })
  return options.withUnit === false ? text : `${text} ${unitLabel(commodity, prefs)}`
}

export function sumAmounts(values: string[]): string {
  return values.reduce((acc, v) => acc.plus(v || '0'), new Big(0)).toFixed()
}

export function compareAmounts(a: string, b: string): number {
  return new Big(a).cmp(b)
}
