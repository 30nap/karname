import { daysBetween } from '../jalali'
import { formatDayOffset, formatMonths, formatTimeAgo } from './duration'

describe('formatMonths', () => {
  it('formats months as years and months in Persian', () => {
    expect(formatMonths(8)).toBe('۸ ماه')
    expect(formatMonths(24)).toBe('۲ سال')
    expect(formatMonths(141)).toBe('۱۱ سال و ۹ ماه')
    expect(formatMonths(13, 'LATIN')).toBe('1 سال و 1 ماه')
  })
})

describe('formatDayOffset', () => {
  it('names nearby days and counts the rest', () => {
    expect(formatDayOffset(0)).toBe('امروز')
    expect(formatDayOffset(1)).toBe('فردا')
    expect(formatDayOffset(-1)).toBe('دیروز')
    expect(formatDayOffset(12)).toBe('۱۲ روز دیگر')
    expect(formatDayOffset(-5, 'LATIN')).toBe('5 روز پیش')
  })
})

describe('daysBetween', () => {
  it('counts calendar days across month and year ends', () => {
    expect(daysBetween('2026-10-06', '2026-10-06')).toBe(0)
    expect(daysBetween('2026-10-06', '2026-10-23')).toBe(17)
    expect(daysBetween('2026-10-23', '2026-10-06')).toBe(-17)
    expect(daysBetween('2026-12-25', '2027-01-21')).toBe(27)
    // UTC arithmetic: a daylight-saving switch on the device does not shift the count
    expect(daysBetween('2026-03-01', '2026-04-01')).toBe(31)
  })
})

describe('formatTimeAgo', () => {
  const now = new Date('2026-10-06T08:30:00Z') // 12:00 in Tehran
  it('counts minutes and hours, then Tehran calendar days', () => {
    expect(formatTimeAgo('2026-10-06T08:29:40Z', 'PERSIAN', now)).toBe('همین حالا')
    expect(formatTimeAgo('2026-10-06T08:05:00Z', 'PERSIAN', now)).toBe('۲۵ دقیقه پیش')
    expect(formatTimeAgo('2026-10-06T02:30:00Z', 'LATIN', now)).toBe('6 ساعت پیش')
    expect(formatTimeAgo('2026-10-05T06:00:00Z', 'PERSIAN', now)).toBe('دیروز')
    expect(formatTimeAgo('2026-10-02T06:00:00Z', 'PERSIAN', now)).toBe('۴ روز پیش')
    expect(formatTimeAgo('2026-09-01T06:00:00Z', 'PERSIAN', now)).toBe('۱۰ شهریور ۱۴۰۵')
  })
})
