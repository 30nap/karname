const ONES = ['', 'یک', 'دو', 'سه', 'چهار', 'پنج', 'شش', 'هفت', 'هشت', 'نه']
const TEENS = ['ده', 'یازده', 'دوازده', 'سیزده', 'چهارده', 'پانزده', 'شانزده', 'هفده', 'هجده', 'نوزده']
const TENS = ['', '', 'بیست', 'سی', 'چهل', 'پنجاه', 'شصت', 'هفتاد', 'هشتاد', 'نود']
const HUNDREDS = ['', 'صد', 'دویست', 'سیصد', 'چهارصد', 'پانصد', 'ششصد', 'هفتصد', 'هشتصد', 'نهصد']
const SCALES = ['', 'هزار', 'میلیون', 'میلیارد', 'تریلیون', 'کوادریلیون', 'کوینتیلیون']
const JOINER = ' و '

function threeDigits(n: number): string {
  const parts: string[] = []
  const h = Math.floor(n / 100)
  const r = n % 100
  if (h) parts.push(HUNDREDS[h])
  if (r) {
    if (r < 10) parts.push(ONES[r])
    else if (r < 20) parts.push(TEENS[r - 10])
    else {
      parts.push(TENS[Math.floor(r / 10)])
      if (r % 10) parts.push(ONES[r % 10])
    }
  }
  return parts.join(JOINER)
}

/**
 * Writes an integer in Persian words, as on Iranian bank receipts:
 * 2500000 → «دو میلیون و پانصد هزار».
 */
export function numberToPersianWords(value: string | number | bigint): string {
  let n: bigint
  try {
    n = BigInt(typeof value === 'string' ? value.split('.')[0] || '0' : typeof value === 'number' ? Math.trunc(value) : value)
  } catch {
    return ''
  }
  if (n === 0n) return 'صفر'
  const negative = n < 0n
  if (negative) n = -n
  const groups: number[] = []
  while (n > 0n) {
    groups.push(Number(n % 1000n))
    n /= 1000n
  }
  if (groups.length > SCALES.length) return ''
  const words: string[] = []
  for (let i = groups.length - 1; i >= 0; i--) {
    if (groups[i] === 0) continue
    const scale = SCALES[i]
    words.push(scale ? `${threeDigits(groups[i])} ${scale}` : threeDigits(groups[i]))
  }
  return (negative ? 'منفی ' : '') + words.join(JOINER)
}
