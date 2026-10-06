import type { RecurringRule } from './api/types'
import type { DigitStyle } from './format/number'
import { JALALI_MONTHS, WEEKDAYS } from './jalali'
import { toPersianDigits } from './persian/digits'

/** «ماهانه، روز ۳», «هر ۲ هفته، جمعه», «سالانه، ۱ فروردین». */
export function describeRecurrence(rule: Pick<RecurringRule, 'frequency' | 'interval' | 'dayOfMonth' | 'dayOfWeek' | 'monthOfYear'>,
  digits: DigitStyle = 'PERSIAN'): string {
  const n = (v: number) => (digits === 'PERSIAN' ? toPersianDigits(String(v)) : String(v))
  const day = rule.dayOfMonth ?? 1
  const dayText = day >= 29 ? `روز ${n(day)} (یا آخرین روز ماه)` : `روز ${n(day)}`
  switch (rule.frequency) {
    case 'WEEKLY':
      return `${rule.interval > 1 ? `هر ${n(rule.interval)} هفته` : 'هفتگی'}، ${WEEKDAYS[rule.dayOfWeek ?? 0]}`
    case 'MONTHLY':
      return `${rule.interval > 1 ? `هر ${n(rule.interval)} ماه` : 'ماهانه'}، ${dayText}`
    case 'YEARLY':
      return `${rule.interval > 1 ? `هر ${n(rule.interval)} سال` : 'سالانه'}، ${n(day)} ${JALALI_MONTHS[(rule.monthOfYear ?? 1) - 1]}`
  }
}
