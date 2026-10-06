/** Alternative wealth measures offered in settings. */
export const WEALTH_UNIT_OPTIONS = [
  { code: 'USD', label: 'دلار آمریکا' },
  { code: 'EUR', label: 'یورو' },
  { code: 'USDT', label: 'تتر' },
  { code: 'GOLD18', label: 'طلای ۱۸ عیار (گرم)' },
  { code: 'COIN_EMAMI', label: 'سکه امامی' },
  { code: 'BTC', label: 'بیت‌کوین' },
] as const

export function useWealthUnitOptions() {
  return WEALTH_UNIT_OPTIONS
}
