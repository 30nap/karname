import { Sparkles, Wand2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { CategorySelect } from '@/components/finance/selects'
import { useFormat } from '@/app/preferences'
import { ApiError } from '@/lib/api/client'
import type { CategorizeSuggestion } from '@/lib/api/types'
import { AiUnavailable } from './AiUnavailable'
import { useAiStatus, useApplyCategories, useCategorize } from './api'

/**
 * AI-suggested categories for uncategorized transactions; accepted ones are applied like manual
 * edits, so the same merchants are categorized automatically next time.
 */
export function CategorizeDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? <CategorizeContent onClose={() => onOpenChange(false)} /> : null}
    </Dialog>
  )
}

function CategorizeContent({ onClose }: { onClose: () => void }) {
  const f = useFormat()
  const { data: status } = useAiStatus()
  const categorize = useCategorize()
  const apply = useApplyCategories()
  const [choices, setChoices] = useState<Record<number, number | null>>({})
  const started = useRef(false)
  const blocked = !!status && (!status.enabled || !status.tasks.EXTRACT)

  const run = useCallback(() => categorize.mutate(undefined, {
    onSuccess: (result) => setChoices(Object.fromEntries(result.suggestions.map((s) => [s.transactionId, s.confidence === 'LOW' ? null : s.categoryId]))),
  }), [categorize])
  // ask once the dialog is open and AI is known to be available
  useEffect(() => {
    if (status && !blocked && !started.current) {
      started.current = true
      run()
    }
  }, [status, blocked, run])

  const result = categorize.data
  const error = categorize.error ? (categorize.error instanceof ApiError ? categorize.error.message : 'پیشنهادی دریافت نشد.') : null
  const accepted = Object.entries(choices).filter(([, c]) => c !== null).map(([id, c]) => ({ transactionId: Number(id), categoryId: c! }))
  const save = () => apply.mutate(accepted, {
    onSuccess: (r) => {
      toast.success(`${f.number(r.updated)} تراکنش دسته‌بندی شد.`)
      setChoices({})
      if (result && result.remaining > 0) run()
      else onClose()
    },
  })

  return (
    <DialogContent className="sm:max-w-2xl">
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2"><Wand2 className="size-5 text-primary" />دسته‌بندی هوشمند</DialogTitle>
        <DialogDescription>برای تراکنش‌های بی‌دسته از روی شرحشان دسته پیشنهاد می‌شود؛ پیش از اعمال، آن‌ها را بررسی کنید.</DialogDescription>
      </DialogHeader>
      <DialogBody className="grid grid-cols-1 gap-3">
        {blocked ? <AiUnavailable status={status} task="EXTRACT" /> : null}
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        {categorize.isPending ? <div className="grid gap-2">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-16 rounded-xl" />)}</div> : null}
        {result && !categorize.isPending ? (
          <>
            <p className="text-sm text-muted-foreground">
              {f.number(result.considered)} تراکنش بررسی شد | {f.number(result.suggestions.length)} پیشنهاد
              {result.remaining > 0 ? ` | ${f.number(result.remaining)} تراکنش دیگر در نوبت` : ''}
            </p>
            {result.suggestions.length === 0 ? <Alert variant="warning">برای این تراکنش‌ها دسته‌ی مناسبی پیدا نشد.</Alert> : null}
            <ul className="grid gap-2">
              {result.suggestions.map((s) => (
                <SuggestionRow key={s.transactionId} suggestion={s} choice={choices[s.transactionId] ?? null}
                  onChange={(c) => setChoices((all) => ({ ...all, [s.transactionId]: c }))} />
              ))}
            </ul>
          </>
        ) : null}
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>بستن</Button>
        <Button onClick={save} loading={apply.isPending} disabled={accepted.length === 0}>
          <Sparkles />اعمال {accepted.length ? f.number(accepted.length) : ''} دسته
        </Button>
      </DialogFooter>
    </DialogContent>
  )
}

function SuggestionRow({ suggestion: s, choice, onChange }: {
  suggestion: CategorizeSuggestion
  choice: number | null
  onChange: (categoryId: number | null) => void
}) {
  const f = useFormat()
  const kind = s.type === 'INCOME' ? 'INCOME' : 'EXPENSE'
  return (
    <li className="grid grid-cols-1 gap-2 rounded-xl border p-3 sm:grid-cols-[auto_minmax(0,1fr)_14rem] sm:items-center">
      <Checkbox aria-label={`پذیرفتن پیشنهاد برای «${s.description}»`} checked={choice !== null}
        onCheckedChange={(v) => onChange(v ? s.categoryId : null)} className="hidden sm:flex" />
      <div className="min-w-0">
        <p className="truncate text-sm font-medium">{s.description}</p>
        <p className="text-xs text-muted-foreground">
          {f.dateShort(s.date)} | <Amount value={s.amount} commodity={s.unit ?? 'IRT'} tone={s.type === 'INCOME' ? 'income' : 'expense'} />
          {s.confidence === 'LOW' ? <Badge variant="warning" className="ms-2">مطمئن نیست</Badge> : null}
        </p>
      </div>
      <CategorySelect kind={kind} value={choice} noneLabel="تغییر نده" onChange={onChange} />
    </li>
  )
}
