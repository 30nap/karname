import { formatMonths } from './duration'

describe('formatMonths', () => {
  it('formats months as years and months in Persian', () => {
    expect(formatMonths(8)).toBe('۸ ماه')
    expect(formatMonths(24)).toBe('۲ سال')
    expect(formatMonths(141)).toBe('۱۱ سال و ۹ ماه')
    expect(formatMonths(13, 'LATIN')).toBe('1 سال و 1 ماه')
  })
})
