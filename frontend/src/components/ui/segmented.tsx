import { cn } from '@/lib/cn'

export interface SegmentOption<T extends string> {
  value: T
  label: string
  className?: string
}

/** Radio-group styled as a segmented control (e.g. expense / income / transfer). */
export function Segmented<T extends string>({ value, onChange, options, ariaLabel, className }: {
  value: T
  onChange: (value: T) => void
  options: SegmentOption<T>[]
  ariaLabel: string
  className?: string
}) {
  return (
    <div role="radiogroup" aria-label={ariaLabel} className={cn('flex rounded-xl bg-muted p-1', className)}>
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              'flex-1 cursor-pointer rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
              active ? cn('bg-card shadow-sm', o.className ?? 'text-foreground') : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
