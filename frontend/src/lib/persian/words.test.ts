import { numberToPersianWords } from './words'

describe('numberToPersianWords', () => {
  it.each([
    [0, 'صفر'],
    [5, 'پنج'],
    [15, 'پانزده'],
    [21, 'بیست و یک'],
    [100, 'صد'],
    [120, 'صد و بیست'],
    [999, 'نهصد و نود و نه'],
    [1000, 'یک هزار'],
    [1001, 'یک هزار و یک'],
    [120000, 'صد و بیست هزار'],
    [2500000, 'دو میلیون و پانصد هزار'],
    [1_000_000_000, 'یک میلیارد'],
    [3_200_000_000, 'سه میلیارد و دویست میلیون'],
  ])('%s → %s', (n, words) => {
    expect(numberToPersianWords(n)).toBe(words)
  })

  it('handles huge values exactly via strings', () => {
    expect(numberToPersianWords('12000000000000')).toBe('دوازده تریلیون')
    expect(numberToPersianWords('2500000.75')).toBe('دو میلیون و پانصد هزار')
  })

  it('handles negatives and garbage', () => {
    expect(numberToPersianWords(-250)).toBe('منفی دویست و پنجاه')
    expect(numberToPersianWords('abc')).toBe('')
  })
})
