import { describeRecurrence } from './recurrence'

describe('describeRecurrence', () => {
  it('describes Jalali schedules in Persian', () => {
    expect(describeRecurrence({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 3, dayOfWeek: null, monthOfYear: null })).toBe('ماهانه، روز ۳')
    expect(describeRecurrence({ frequency: 'MONTHLY', interval: 3, dayOfMonth: 31, dayOfWeek: null, monthOfYear: null }))
      .toBe('هر ۳ ماه، روز ۳۱ (یا آخرین روز ماه)')
    expect(describeRecurrence({ frequency: 'WEEKLY', interval: 2, dayOfMonth: null, dayOfWeek: 6, monthOfYear: null })).toBe('هر ۲ هفته، جمعه')
    expect(describeRecurrence({ frequency: 'YEARLY', interval: 1, dayOfMonth: 1, dayOfWeek: null, monthOfYear: 1 })).toBe('سالانه، ۱ فروردین')
  })
})
