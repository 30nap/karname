import { PenLine, Sparkles } from 'lucide-react'
import { useState, type KeyboardEvent } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/input'
import { ApiError } from '@/lib/api/client'
import { AiUnavailable } from './AiUnavailable'
import { DraftList } from './DraftList'
import { useAiStatus, useQuickAdd } from './api'

const MAX_NOTE = 2000

/** «دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن» → drafts to check and record. Lives inside the transaction dialog. */
export function QuickAddPanel({ onManual }: { onManual: () => void }) {
  const { data: status } = useAiStatus()
  const quickAdd = useQuickAdd()
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const read = () => {
    if (!text.trim()) return
    setError(null)
    quickAdd.mutate(text, { onError: (e) => setError(e instanceof ApiError ? e.message : 'متن خوانده نشد.') })
  }
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      read()
    }
  }
  const blocked = status && (!status.enabled || !status.tasks.EXTRACT)
  const result = quickAdd.data
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2"><Sparkles className="size-5 text-primary" />ثبت با متن</DialogTitle>
        <DialogDescription>هر چند تراکنش را در یک جمله بنویسید؛ پیش از ثبت آن‌ها را می‌بینید.</DialogDescription>
      </DialogHeader>
      <DialogBody className="grid grid-cols-1 gap-3">
        {blocked ? <AiUnavailable status={status} task="EXTRACT" /> : (
          <>
            <Textarea value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} maxLength={MAX_NOTE} rows={3} autoFocus
              aria-label="شرح تراکنش‌ها" placeholder="مثلاً: دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن از کارت ملت" />
            {error ? <Alert variant="destructive">{error}</Alert> : null}
            {result?.note ? <Alert variant="warning">{result.note}</Alert> : null}
            {result && result.drafts.length === 0 && !result.note ? <Alert variant="warning">تراکنشی در متن پیدا نشد.</Alert> : null}
            {result?.drafts.length ? <DraftList key={result.drafts.map((d) => d.ref).join()} drafts={result.drafts} /> : null}
          </>
        )}
      </DialogBody>
      <DialogFooter>
        <Button variant="ghost" className="sm:me-auto" onClick={onManual}><PenLine />فرم دستی</Button>
        {blocked ? null : <Button onClick={read} loading={quickAdd.isPending} disabled={!text.trim()}><Sparkles />خواندن</Button>}
      </DialogFooter>
    </DialogContent>
  )
}
