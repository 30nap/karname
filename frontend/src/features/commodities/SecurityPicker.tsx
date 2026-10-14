import { Search } from 'lucide-react'
import { useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import type { SecurityInstrument } from '@/lib/api/types'
import { useSearchSecurities } from './api'

/** Search the exchange by a human-readable symbol; the instrument identifier stays internal. */
export function SecurityPicker({ value, onChange }: { value: SecurityInstrument | null; onChange: (value: SecurityInstrument | null) => void }) {
  const [text, setText] = useState('')
  const [term, setTerm] = useState<string | null>(null)
  const search = useSearchSecurities(term)
  const current = term === text.trim()

  return (
    <div className="grid gap-3">
      <form className="flex items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        if (text.trim().length < 2) return
        onChange(null)
        if (current) void search.refetch()
        else setTerm(text.trim())
      }}>
        <FormField label="نماد یا نام شرکت و صندوق" hint="دست‌کم دو حرف بنویسید؛ مثلاً فولاد یا عیار." className="flex-1">
          <Input value={text} maxLength={60} placeholder="جست‌وجوی نماد در بورس ایران" onChange={(e) => {
            setText(e.target.value)
            onChange(null)
          }} />
        </FormField>
        <Button type="submit" variant="outline" className="mb-6" loading={current && search.isFetching} disabled={text.trim().length < 2}>
          <Search />جست‌وجو
        </Button>
      </form>
      {current && search.isError ? <Alert variant="destructive">{search.error.message}</Alert> : null}
      {current && search.data ? (
        search.data.length ? (
          <div role="radiogroup" aria-label="نتایج جست‌وجوی نماد" className="max-h-56 overflow-y-auto rounded-lg border">
            {search.data.map((instrument) => (
              <button key={instrument.instrumentId} type="button" role="radio" aria-checked={value?.instrumentId === instrument.instrumentId}
                className="flex w-full cursor-pointer items-center justify-between gap-3 border-b p-3 text-start last:border-b-0 hover:bg-accent/60 aria-checked:bg-primary/10"
                onClick={() => onChange(instrument)}>
                <span><span className="block font-medium">{instrument.symbol}</span><span className="text-xs text-muted-foreground">{instrument.nameFa}</span></span>
                {value?.instrumentId === instrument.instrumentId ? <span className="text-xs text-primary">انتخاب‌شده</span> : null}
              </button>
            ))}
          </div>
        ) : <p role="status" className="text-sm text-muted-foreground">نمادی پیدا نشد. نام یا نماد دیگری را جست‌وجو کنید.</p>
      ) : null}
    </div>
  )
}
