import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { usePrefs } from '@/app/preferences'
import {
  addDaysIso, formatJalaliLong, fromJalali, JALALI_MONTHS, jalaliMonthLength, jalaliWeekdayIndex, toJalali, todayIso,
  WEEKDAYS, WEEKDAYS_SHORT,
} from '@/lib/jalali'
import { toPersianDigits } from '@/lib/persian/digits'
import { cn } from '@/lib/cn'

interface JalaliDatePickerProps {
  /** ISO date (YYYY-MM-DD). */
  value: string
  onChange: (iso: string) => void
  id?: string
  disabled?: boolean
  className?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-label'?: string
}

/** Solar Hijri date picker; the week starts on Saturday. */
export function JalaliDatePicker({ value, onChange, id, disabled, className, ...aria }: JalaliDatePickerProps) {
  const prefs = usePrefs()
  const digits = (n: number | string) => (prefs.digits === 'PERSIAN' ? toPersianDigits(String(n)) : String(n))
  const [open, setOpen] = useState(false)
  const selected = toJalali(value || todayIso())
  const [view, setView] = useState({ year: selected.year, month: selected.month })
  const [focusIso, setFocusIso] = useState(value || todayIso())
  const today = todayIso()
  const gridRef = useRef<HTMLDivElement>(null)
  const keyboardMove = useRef(false)

  // Roving focus: after an arrow key, move DOM focus to the newly focused day.
  useEffect(() => {
    if (!keyboardMove.current) return
    keyboardMove.current = false
    gridRef.current?.querySelector<HTMLButtonElement>(`[data-iso="${focusIso}"]`)?.focus()
  }, [focusIso, view])

  const openPicker = (next: boolean) => {
    if (next) {
      const base = toJalali(value || today)
      setView({ year: base.year, month: base.month })
      setFocusIso(value || today)
    }
    setOpen(next)
  }

  const shiftMonth = (delta: number) => {
    setView((v) => {
      const index = v.year * 12 + (v.month - 1) + delta
      return { year: Math.floor(index / 12), month: (index % 12) + 1 }
    })
  }

  const select = (iso: string) => {
    onChange(iso)
    setOpen(false)
  }

  const moveFocus = (days: number) => {
    const next = addDaysIso(focusIso, days)
    keyboardMove.current = true
    setFocusIso(next)
    const j = toJalali(next)
    setView({ year: j.year, month: j.month })
  }

  const onGridKeyDown = (e: KeyboardEvent) => {
    // RTL grid: left arrow moves to the next day
    const moves: Record<string, number> = { ArrowLeft: 1, ArrowRight: -1, ArrowDown: 7, ArrowUp: -7 }
    if (e.key in moves) {
      e.preventDefault()
      moveFocus(moves[e.key])
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      select(focusIso)
    }
  }

  const length = jalaliMonthLength(view.year, view.month)
  const firstIso = fromJalali(view.year, view.month, 1)
  const offset = jalaliWeekdayIndex(firstIso)
  const cells: (string | null)[] = [...Array(offset).fill(null), ...Array.from({ length }, (_, i) => fromJalali(view.year, view.month, i + 1))]

  return (
    <Popover open={open} onOpenChange={openPicker}>
      <PopoverTrigger asChild>
        <button
          type="button"
          id={id}
          disabled={disabled}
          className={cn(
            'flex h-10 w-full cursor-pointer items-center justify-between gap-2 rounded-lg border border-input bg-card px-3 text-sm shadow-xs',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40 disabled:opacity-60 aria-invalid:border-destructive',
            className,
          )}
          {...aria}
        >
          <span>{value ? formatJalaliLong(value, prefs.digits) : 'انتخاب تاریخ'}</span>
          <CalendarDays className="size-4 text-muted-foreground" />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-[19rem] p-3" align="start">
        <div className="mb-2 flex items-center justify-between">
          <button type="button" onClick={() => shiftMonth(-1)} className="rounded-md p-1.5 hover:bg-accent" aria-label="ماه قبل">
            <ChevronRight className="size-4" />
          </button>
          <span className="text-sm font-semibold" aria-live="polite">{JALALI_MONTHS[view.month - 1]} {digits(view.year)}</span>
          <button type="button" onClick={() => shiftMonth(1)} className="rounded-md p-1.5 hover:bg-accent" aria-label="ماه بعد">
            <ChevronLeft className="size-4" />
          </button>
        </div>
        <div ref={gridRef} role="group" aria-label={`${JALALI_MONTHS[view.month - 1]} ${digits(view.year)}`} onKeyDown={onGridKeyDown} className="grid grid-cols-7 gap-1 text-center">
          {WEEKDAYS_SHORT.map((d, i) => (
            <abbr key={d} title={WEEKDAYS[i]} className="pb-1 text-xs font-medium text-muted-foreground no-underline" aria-hidden>{d}</abbr>
          ))}
          {cells.map((iso, i) =>
            iso === null ? (
              <span key={`blank-${i}`} />
            ) : (
              <button
                key={iso}
                type="button"
                data-iso={iso}
                tabIndex={iso === focusIso ? 0 : -1}
                aria-pressed={iso === value}
                aria-current={iso === today ? 'date' : undefined}
                aria-label={formatJalaliLong(iso, prefs.digits)}
                onClick={() => select(iso)}
                onFocus={() => setFocusIso(iso)}
                className={cn(
                  'flex h-9 cursor-pointer items-center justify-center rounded-lg text-sm transition-colors hover:bg-accent',
                  iso === value && 'bg-primary text-primary-foreground hover:bg-primary/90',
                  iso === today && iso !== value && 'font-bold text-primary ring-1 ring-primary/40',
                  jalaliWeekdayIndex(iso) === 6 && iso !== value && 'text-expense',
                )}
              >
                {digits(toJalali(iso).day)}
              </button>
            ),
          )}
        </div>
        <div className="mt-3 flex gap-2 border-t pt-3">
          <button type="button" className="rounded-md px-2 py-1 text-xs font-medium text-primary hover:bg-accent" onClick={() => select(today)}>امروز</button>
          <button type="button" className="rounded-md px-2 py-1 text-xs font-medium text-primary hover:bg-accent" onClick={() => select(addDaysIso(today, -1))}>دیروز</button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
