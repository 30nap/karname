import { forwardRef, useLayoutEffect, useRef, useState, type ChangeEvent } from 'react'
import { usePrefs } from '@/app/preferences'
import { unitLabel, type CommodityDisplay } from '@/lib/format/money'
import { numberToPersianWords } from '@/lib/persian/words'
import { cn } from '@/lib/cn'
import { formatTyping, normalizeTyping } from '@/lib/format/typing'

interface MoneyInputProps {
  /** Plain decimal string in the commodity's display unit ('' when empty). */
  value: string
  onChange: (value: string) => void
  commodity: CommodityDisplay
  id?: string
  placeholder?: string
  autoFocus?: boolean
  allowNegative?: boolean
  showWords?: boolean
  showQuickButtons?: boolean
  className?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-label'?: string
}

/** Amount field: live thousands separators, Persian digits, unit suffix and the amount in words. */
export const MoneyInput = forwardRef<HTMLInputElement, MoneyInputProps>(function MoneyInput(
  { value, onChange, commodity, id, placeholder, autoFocus, allowNegative, showWords = true, showQuickButtons, className, ...aria },
  forwardedRef,
) {
  const prefs = usePrefs()
  const persian = prefs.digits === 'PERSIAN'
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [typed, setTyped] = useState<string>(value)
  const [synced, setSynced] = useState<string>(value)
  const caret = useRef<number | null>(null)
  // An external change (form reset, edit dialog) replaces what was typed; our own changes don't.
  if (value !== synced) {
    setSynced(value)
    setTyped(value)
  }
  const text = formatTyping(typed, persian)

  useLayoutEffect(() => {
    if (caret.current === null || !inputRef.current) return
    const digitsBefore = caret.current
    let pos = 0
    let seen = 0
    while (pos < text.length && seen < digitsBefore) {
      if (/[\d۰-۹.٫-]/.test(text[pos])) seen++
      pos++
    }
    inputRef.current.setSelectionRange(pos, pos)
    caret.current = null
  }, [text])

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value
    const latin = normalizeTyping(raw, !!allowNegative)
    if (latin === null) return
    const selection = e.target.selectionStart ?? raw.length
    caret.current = normalizeTyping(raw.slice(0, selection), true)?.length ?? null
    setTyped(latin)
    const normalized = latin === '' || latin === '-' || latin === '.' ? '' : latin.replace(/\.$/, '')
    setSynced(normalized)
    onChange(normalized)
  }

  const multiply = (zeros: string) => {
    if (!value || value.includes('.')) return
    const next = value + zeros
    setTyped(next)
    setSynced(next)
    onChange(next)
    inputRef.current?.focus()
  }

  const unit = unitLabel(commodity, prefs)
  const isToman = commodity.code === 'IRT'
  const words = showWords && isToman && value && /^\d+$/.test(value) ? numberToPersianWords(value) : ''

  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <div className="flex gap-2">
        <div className="relative flex-1">
          <input
            ref={(node) => {
              inputRef.current = node
              if (typeof forwardedRef === 'function') forwardedRef(node)
              else if (forwardedRef) forwardedRef.current = node
            }}
            id={id}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            dir="ltr"
            placeholder={placeholder}
            autoFocus={autoFocus}
            value={text}
            onChange={handleChange}
            className={cn(
              'h-11 w-full rounded-lg border border-input bg-card ps-3 pe-16 text-end text-lg font-semibold shadow-xs',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40 aria-invalid:border-destructive',
            )}
            {...aria}
          />
          <span className="pointer-events-none absolute inset-y-0 start-3 flex items-center text-sm text-muted-foreground" aria-hidden>
            {unit}
          </span>
        </div>
        {showQuickButtons && isToman ? (
          <div className="flex gap-1">
            <button type="button" onClick={() => multiply('000')} className="h-11 cursor-pointer rounded-lg border px-2.5 text-xs font-medium hover:bg-accent">هزار</button>
            <button type="button" onClick={() => multiply('000000')} className="h-11 cursor-pointer rounded-lg border px-2.5 text-xs font-medium hover:bg-accent">میلیون</button>
          </div>
        ) : null}
      </div>
      {words ? <p className="text-xs text-muted-foreground" aria-live="polite">{words} {unit}</p> : null}
    </div>
  )
})
