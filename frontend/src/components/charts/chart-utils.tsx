import { ChartColumn, Table2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/** Consistent tooltip surface for Recharts custom tooltips (rendered as HTML, right-to-left). */
export function ChartTooltipBox({ title, children }: { title?: ReactNode; children: ReactNode }) {
  return (
    <div dir="rtl" className="min-w-36 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
      {title ? <p className="mb-1 font-medium">{title}</p> : null}
      <div className="grid gap-0.5">{children}</div>
    </div>
  )
}

export function TooltipRow({ color, label, value }: { color?: string; label: ReactNode; value: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="flex items-center gap-1.5 text-muted-foreground">
        {color ? <span className="size-2 rounded-sm" style={{ background: color }} aria-hidden /> : null}
        {label}
      </span>
      <span className="font-medium">{value}</span>
    </div>
  )
}

export type ChartView = 'chart' | 'table'

/** Switches a chart card between the chart and its table view (every chart has one). */
export function ChartViewToggle({ value, onChange }: { value: ChartView; onChange: (view: ChartView) => void }) {
  const options: { view: ChartView; label: string; icon: typeof Table2 }[] = [
    { view: 'chart', label: 'نمایش نمودار', icon: ChartColumn },
    { view: 'table', label: 'نمایش جدول', icon: Table2 },
  ]
  return (
    <div role="radiogroup" aria-label="نوع نمایش" className="flex rounded-lg bg-muted p-0.5">
      {options.map(({ view, label, icon: Icon }) => (
        <button
          key={view}
          type="button"
          role="radio"
          aria-checked={value === view}
          aria-label={label}
          title={label}
          onClick={() => onChange(view)}
          className={cn(
            'flex size-7 cursor-pointer items-center justify-center rounded-md transition-colors',
            value === view ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <Icon className="size-4" />
        </button>
      ))}
    </div>
  )
}
