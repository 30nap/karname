import { toLatinDigits, toPersianDigits } from '@/lib/persian/digits'

const GROUP = '٬' // Persian thousands separator
const DECIMAL = '٫'

function groupDigits(int: string, persian: boolean) {
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, persian ? GROUP : ',')
  return persian ? toPersianDigits(grouped) : grouped
}

/** Formats a partially typed number ("1234.5", "12.") for display, keeping a trailing decimal point. */
export function formatTyping(latin: string, persian: boolean): string {
  if (latin === '' || latin === '-') return latin
  const negative = latin.startsWith('-')
  const body = negative ? latin.slice(1) : latin
  const [int, frac] = body.split('.')
  const intText = groupDigits(int.replace(/^0+(?=\d)/, '') || (frac !== undefined ? '0' : ''), persian)
  const fracText = frac === undefined ? '' : (persian ? DECIMAL : '.') + (persian ? toPersianDigits(frac) : frac)
  return (negative ? '-' : '') + intText + fracText
}

/** Normalizes what the user typed into a Latin decimal while typing, or null if it can't be a number. */
export function normalizeTyping(raw: string, allowNegative: boolean): string | null {
  const latin = toLatinDigits(raw)
    .replace(/[٬,\s،‌]/g, '')
    .replace(/[٫/]/g, '.')
    .replace(/−/g, '-')
  const pattern = allowNegative ? /^-?\d*(\.\d*)?$/ : /^\d*(\.\d*)?$/
  return pattern.test(latin) ? latin : null
}
