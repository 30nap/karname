import { parseDecimalInput, toLatinDigits, toPersianDigits } from './digits'
import { normalizeForSearch } from './text'

describe('digits', () => {
  it('converts between digit sets', () => {
    expect(toLatinDigits('۱۲۳٤٥٦')).toBe('123456')
    expect(toPersianDigits('1405/07/14')).toBe('۱۴۰۵/۰۷/۱۴')
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
