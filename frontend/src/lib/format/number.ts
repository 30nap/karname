export type DigitStyle = 'PERSIAN' | 'LATIN'

const cache = new Map<string, Intl.NumberFormat>()

function formatter(digits: DigitStyle, options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const locale = digits === 'PERSIAN' ? 'fa-IR' : 'en-US'
  const key = locale + JSON.stringify(options)
  let nf = cache.get(key)
  if (!nf) {
    nf = new Intl.NumberFormat(locale, options)
    cache.set(key, nf)
  }
  return nf
}

export interface NumberFormatOptions {
  digits?: DigitStyle
  maxFraction?: number
  minFraction?: number
  signDisplay?: Intl.NumberFormatOptions['signDisplay']
}

/**
 * Formats a decimal given as a string without converting it to a float (Intl accepts numeric
 * strings), so large Toman amounts and 8-decimal crypto quantities stay exact.
 */
export function formatNumber(value: string | number, options: NumberFormatOptions = {}): string {
  const nf = formatter(options.digits ?? 'PERSIAN', {
    maximumFractionDigits: options.maxFraction ?? 2,
    minimumFractionDigits: options.minFraction ?? 0,
    signDisplay: options.signDisplay ?? 'auto',
  })
  return nf.format(value as Intl.StringNumericLiteral)
}

/** Short form for charts and tiles: «۲٫۵ میلیون», «۱۲۰ هزار». */
export function formatCompact(value: string | number, digits: DigitStyle = 'PERSIAN'): string {
  const nf = formatter(digits, { notation: 'compact', maximumFractionDigits: 1 })
  return nf.format(Number(value))
}

export function formatPercent(value: number, digits: DigitStyle = 'PERSIAN', maxFraction = 0): string {
  return formatter(digits, { style: 'percent', maximumFractionDigits: maxFraction }).format(value)
}
