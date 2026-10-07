import { parseDecimalInput, toLatinDigits, toPersianDigits, toPersianNumerals } from './digits'
import { normalizeForSearch } from './text'

describe('digits', () => {
  it('converts between digit sets', () => {
    expect(toLatinDigits('۱۲۳٤٥٦')).toBe('123456')
    expect(toPersianDigits('1405/07/14')).toBe('۱۴۰۵/۰۷/۱۴')
  })

  it('writes the numbers of free text in Persian form', () => {
    expect(toPersianNumerals('مسکن: 18,000,000 تومان (63.2%)')).toBe('مسکن: ۱۸٬۰۰۰٬۰۰۰ تومان (۶۳٫۲٪)')
    expect(toPersianNumerals('تاریخ 1405/07/14، گزینه‌های 1,2 و 20 %')).toBe('تاریخ ۱۴۰۵/۰۷/۱۴، گزینه‌های ۱,۲ و ۲۰٪')
  })

  it.each([
    ['۱٬۲۵۰٬۰۰۰', '1250000'],
    ['1,250,000', '1250000'],
    ['۲٫۵', '2.5'],
    ['0.00012000', '0.00012'],
    ['  ۳۰۰ ', '300'],
    ['007', '7'],
    ['-12.50', '-12.5'],
  ])('parses %s', (input, expected) => {
    expect(parseDecimalInput(input)).toBe(expected)
  })

  it.each(['', 'abc', '1.2.3', '۱۲ت'])('rejects %s', (input) => {
    expect(parseDecimalInput(input)).toBeNull()
  })

  it('normalizes Arabic letters for search', () => {
    expect(normalizeForSearch('ميوه كرج')).toBe('میوه کرج')
    expect(normalizeForSearch('اسنپ‌فود  SNAPP')).toBe('اسنپ فود snapp')
  })
})
