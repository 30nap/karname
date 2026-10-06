import { cn } from '@/lib/cn'

interface ProgressProps {
  /** 0..1 (values above 1 are shown as full and in the "over" color). */
  value: number
  className?: string
  tone?: 'primary' | 'income' | 'expense' | 'warning' | 'auto'
  label?: string
}

/** Horizontal progress bar; with tone="auto" it turns amber at 80% and red when over 100%. */
export function Progress({ value, className, tone = 'primary', label }: ProgressProps) {
  const clamped = Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0))
  const resolved = tone === 'auto' ? (value > 1 ? 'expense' : value >= 0.8 ? 'warning' : 'income') : tone
  const color = {
    primary: 'bg-primary',
    income: 'bg-income',
    expense: 'bg-expense',
    warning: 'bg-warning',
  }[resolved]
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(clamped * 100)}
      aria-label={label}
      className={cn('h-2 w-full overflow-hidden rounded-full bg-muted', className)}
    >
      <div className={cn('h-full rounded-full transition-[width] duration-500', color)} style={{ width: `${clamped * 100}%` }} />
    </div>
  )
}
