/**
 * The rows of a time series from the first one with data. Months before a user's records begin
 * are unknown rather than zero; plotting them as zero draws a long flat line and a false jump.
 */
export function fromFirstData<T>(rows: readonly T[], hasData: (row: T) => boolean): T[] {
  const first = rows.findIndex(hasData)
  return first < 0 ? [] : rows.slice(first)
}
