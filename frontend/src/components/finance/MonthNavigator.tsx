import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useFormat } from '@/app/preferences'
import { addMonthsToKey, currentMonthKey } from '@/lib/jalali'
import { cn } from '@/lib/cn'

/** Previous / next Jalali month switcher; clicking the label returns to the current month. */
export function MonthNavigator({ value, onChange, className, max }: {
  value: string
  onChange: (month: string) => void
  className?: string
  /** Last selectable month (inclusive). */
  max?: string
}) {
  const f = useFormat()
  const current = currentMonthKey()
  const atMax = max !== undefined && value >= max
  return (
    <div className={cn('flex items-center justify-between rounded-lg border bg-card', className)}>
      <Button variant="ghost" size="icon-sm" aria-label="ماه قبل" onClick={() => onChange(addMonthsToKey(value, -1))}>
        <ChevronRight />
      </Button>
      <button
        type="button"
        className="min-w-28 cursor-pointer px-2 text-sm font-medium"
        onClick={() => onChange(current)}
        title={value === current ? undefined : 'برگشت به ماه جاری'}
        aria-live="polite"
      >
        {f.month(value)}
      </button>
      <Button variant="ghost" size="icon-sm" aria-label="ماه بعد" disabled={atMax} onClick={() => onChange(addMonthsToKey(value, 1))}>
        <ChevronLeft />
      </Button>
    </div>
  )
}
