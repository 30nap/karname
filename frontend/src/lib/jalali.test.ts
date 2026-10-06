import {
  addMonthsToKey, currentMonthKey, formatJalaliLong, formatJalaliShort, formatJalaliWithWeekday,
  fromJalali, jalaliMonthLength, jalaliWeekdayIndex, monthLabel, monthRange, toJalali, todayIso,
} from './jalali'

describe('jalali helpers', () => {
  it('converts known dates both ways', () => {
    expect(toJalali('2026-10-06')).toEqual({ year: 1405, month: 7, day: 14 })
    expect(toJalali('2026-03-21')).toEqual({ year: 1405, month: 1, day: 1 })
    expect(toJalali('2025-03-20')).toEqual({ year: 1403, month: 12, day: 30 })
    expect(fromJalali(1405, 7, 14)).toBe('2026-10-06')
    expect(fromJalali(1357, 11, 22)).toBe('1979-02-11')
  })

  it('knows month lengths including leap Esfand', () => {
    expect(jalaliMonthLength(1405, 6)).toBe(31)
    expect(jalaliMonthLength(1405, 7)).toBe(30)
    expect(jalaliMonthLength(1405, 12)).toBe(29)
    expect(jalaliMonthLength(1403, 12)).toBe(30)
  })

  it('formats dates', () => {
    expect(formatJalaliShort('2026-10-06')).toBe('۱۴۰۵/۰۷/۱۴')
    expect(formatJalaliShort('2026-10-06', 'LATIN')).toBe('1405/07/14')
    expect(formatJalaliLong('2026-10-06')).toBe('۱۴ مهر ۱۴۰۵')
    expect(formatJalaliWithWeekday('2026-10-06')).toBe('سه‌شنبه ۱۴ مهر')
    expect(jalaliWeekdayIndex('2026-10-03')).toBe(0) // Saturday
  })

  it('works with month keys', () => {
    expect(currentMonthKey('2026-10-06')).toBe('1405-07')
    expect(addMonthsToKey('1405-07', 6)).toBe('1406-01')
    expect(addMonthsToKey('1405-01', -1)).toBe('1404-12')
    expect(monthLabel('1405-07')).toBe('مهر ۱۴۰۵')
    expect(monthRange('1405-07')).toEqual({ start: '2026-09-23', end: '2026-10-22' })
  })

  it('computes today in Tehran regardless of device time zone', () => {
    // 2026-10-06T21:00Z is already 7 October (00:30) in Tehran.
    expect(todayIso(new Date('2026-10-06T21:00:00Z'))).toBe('2026-10-07')
    expect(todayIso(new Date('2026-10-06T20:00:00Z'))).toBe('2026-10-06')
  })
})
