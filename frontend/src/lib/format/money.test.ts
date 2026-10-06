import { formatMoney, fromDisplayAmount, sumAmounts, toDisplayAmount } from './money'
import { formatCompact, formatNumber } from './number'

const IRT = { code: 'IRT', unitFa: 'تومان', scale: 0 }
const BTC = { code: 'BTC', unitFa: 'بیت‌کوین', scale: 8 }
const persianToman = { digits: 'PERSIAN', displayUnit: 'TOMAN' } as const
const latinRial = { digits: 'LATIN', displayUnit: 'RIAL' } as const

describe('money formatting', () => {
  it('formats Toman with Persian digits and separators', () => {
    expect(formatMoney('2500000', IRT, persianToman)).toBe('۲٬۵۰۰٬۰۰۰ تومان')
  })

  it('converts to Rial for display only', () => {
    expect(formatMoney('2500000', IRT, latinRial)).toBe('25,000,000 ریال')
    expect(toDisplayAmount('1234.5', 'IRT', latinRial)).toBe('12345')
    expect(fromDisplayAmount('12345', 'IRT', latinRial)).toBe('1234.5')
  })

  it('keeps crypto precision exact', () => {
    expect(formatMoney('0.00012345', BTC, latinRial)).toBe('0.00012345 بیت‌کوین')
  })

  it('formats huge amounts without float rounding', () => {
    expect(formatNumber('123456789012345678', { digits: 'LATIN' })).toBe('123,456,789,012,345,678')
  })

  it('formats compact numbers in Persian', () => {
    // Intl separates the scale word with a no-break space
    expect(formatCompact('2500000').replace(/\u00A0/g, ' ')).toBe('۲٫۵ میلیون')
  })

  it('sums decimal strings exactly', () => {
    expect(sumAmounts(['0.1', '0.2', '100'])).toBe('100.3')
  })

  it('handles missing amounts', () => {
    expect(formatMoney(null, IRT, persianToman)).toBe('—')
  })
})
