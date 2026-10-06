const PERSIAN_ZERO = 0x06f0
const ARABIC_ZERO = 0x0660

/** Converts Persian and Arabic-Indic digits to ASCII digits. */
export function toLatinDigits(text: string): string {
  return text.replace(/[۰-۹٠-٩]/g, (ch) => {
    const code = ch.charCodeAt(0)
    return String(code >= PERSIAN_ZERO ? code - PERSIAN_ZERO : code - ARABIC_ZERO)
  })
}

/** Converts ASCII digits to Persian digits. */
export function toPersianDigits(text: string): string {
  return text.replace(/[0-9]/g, (d) => String.fromCharCode(PERSIAN_ZERO + Number(d)))
}

/**
 * Normalizes a user-typed amount ("۱٬۲۵۰٬۰۰۰", "1,250,000", "۲٫۵") to a plain decimal string
 * ("1250000", "2.5"). Returns null when the text is not a number.
 */
export function parseDecimalInput(text: string): string | null {
  const s = toLatinDigits(text)
    .replace(/٫/g, '.')
    .replace(/[٬،,\s‌]/g, '')
    .replace(/−/g, '-')
  if (s === '' || !/^-?\d+(\.\d+)?$/.test(s)) return null
  const negative = s.startsWith('-')
  const [intPart, frac] = (negative ? s.slice(1) : s).split('.')
  const int = intPart.replace(/^0+(?=\d)/, '')
  const fraction = frac?.replace(/0+$/, '')
  const result = fraction ? `${int}.${fraction}` : int
  return negative && result !== '0' ? `-${result}` : result
}
