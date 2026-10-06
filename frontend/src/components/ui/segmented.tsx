import { cn } from '@/lib/cn'

export interface SegmentOption<T extends string> {
  value: T
  label: string
  className?: string
}

/** Radio-group styled as a segmented control (e.g. expense / income / transfer). */
export function Segmented<T extends string>({ value, onChange, options, ariaLabel, className, size = 'md' }: {
  value: T
  onChange: (value: T) => void
  options: SegmentOption<T>[]
  ariaLabel: string
  className?: string
  size?: 'sm' | 'md'
}) {
  return (
    <div role="radiogroup" aria-label={ariaLabel} className={cn('flex rounded-xl bg-muted', size === 'sm' ? 'p-0.5' : 'p-1', className)}>
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
              'flex-1 cursor-pointer whitespace-nowrap rounded-lg font-medium transition-colors',
              size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-sm',
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
