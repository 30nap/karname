import { AlertTriangle, FileUp } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { FormField } from '@/components/ui/form-field'
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Amount } from '@/components/finance/Amount'
import { AccountSelect } from '@/components/finance/selects'
import { useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCategories } from '@/features/categories/api'
import { everydayAccount } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { DateStyle, ImportMapping, ImportPreview, ImportRow, PriceUnit } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { sumAmounts } from '@/lib/format/money'
import { useImportCommit, useImportPreview } from './api'

const NONE = '__none__'
/** Rows drawn before «نمایش همه»; statements run to a couple of thousand lines. */
const SHOWN = 100

type Role = 'date' | 'description' | 'amount' | 'debit' | 'credit'
const ROLES: { role: Role; label: string }[] = [
  { role: 'date', label: 'تاریخ' },
  { role: 'description', label: 'شرح' },
  { role: 'debit', label: 'برداشت' },
  { role: 'credit', label: 'واریز' },
  { role: 'amount', label: 'مبلغ (با علامت)' },
]

/** Rows worth recording by default: readable and not already there. */
const preselect = (preview: ImportPreview) => new Set(preview.rows.filter((r) => !r.error && !r.duplicate).map((r) => r.line))

export function ImportWizard() {
  const f = useFormat()
  const { data: accounts = [] } = useAccounts()
  const { data: categories = [] } = useCategories()
  const preview = useImportPreview()
  const commit = useImportCommit()
  const fileInput = useRef<HTMLInputElement>(null)
  const tomanAccounts = (a: { commodity: string }) => a.commodity === 'IRT'
  const [accountChoice, setAccountId] = useState<number | null>(null)
  const accountId = accountChoice ?? everydayAccount(accounts)?.id ?? null
  const [file, setFile] = useState<File | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [showAll, setShowAll] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const categoryNames = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories])

  const data = preview.data
  const mapping = data?.mapping
  const columns = data ? Math.max(data.headers.length, ...data.sample.map((r) => r.length)) : 0
  const columnLabel = (i: number) => (data?.headers[i]?.trim() ? data.headers[i] : `ستون ${f.number(i + 1)}`)

  /** Previews again with what changed; {@code mapping: null} lets the server detect the columns (a new file). */
  const load = (next: { file?: File; accountId?: number; mapping?: ImportMapping | null }) => {
    const target = next.file ?? file
    const account = next.accountId ?? accountId
    if (!target || account === null) return
    setError(null)
    const columns = next.mapping === null ? undefined : next.mapping ?? mapping
    preview.mutate({ file: target, accountId: account, mapping: columns }, {
      onSuccess: (result) => { setSelected(preselect(result)); setShowAll(false) },
      onError: (e) => setError(e instanceof ApiError ? e.message : 'فایل خوانده نشد.'),
    })
  }

  const setRole = (role: Role, column: number | null) => {
    if (!mapping) return
    const next: ImportMapping = { ...mapping, [role]: column }
    // one signed amount column, or separate withdrawal/deposit columns
    if (role === 'amount' && column !== null) Object.assign(next, { debit: null, credit: null })
    if ((role === 'debit' || role === 'credit') && column !== null) next.amount = null
    load({ mapping: next })
  }

  const chosen = (data?.rows ?? []).filter((r) => selected.has(r.line))
  const outflow = sumAmounts(chosen.filter((r) => r.amount?.startsWith('-')).map((r) => r.amount!.slice(1)))
  const inflow = sumAmounts(chosen.filter((r) => r.amount && !r.amount.startsWith('-')).map((r) => r.amount!))
  const rows = data ? (showAll ? data.rows : data.rows.slice(0, SHOWN)) : []
  const toggle = (row: ImportRow, on: boolean) => {
    const next = new Set(selected)
    if (on) next.add(row.line)
    else next.delete(row.line)
    setSelected(next)
  }

  const record = () => {
    if (accountId === null) return
    commit.mutate({ accountId, rows: chosen }, {
      onSuccess: (result) => {
        toast.success(`${f.number(result.created)} تراکنش ثبت شد${result.skipped ? `؛ ${f.number(result.skipped)} ردیف قبلاً ثبت شده بود` : ''}.`)
        setFile(null)
        preview.reset()
        if (fileInput.current) fileInput.current.value = ''
      },
    })
  }

  return (
    <div className="grid grid-cols-1 gap-4">
      <div className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2">
        <FormField label="به حساب">
          <AccountSelect value={accountId} filter={tomanAccounts} onChange={(id) => { setAccountId(id); if (id !== null) load({ accountId: id }) }} />
        </FormField>
        <div className="grid gap-1.5">
          <input ref={fileInput} id="statement-file" type="file" accept=".csv,text/csv" className="sr-only" aria-label="فایل صورت‌حساب"
            onChange={(e) => {
              const chosenFile = e.target.files?.[0]
              if (!chosenFile) return
              setFile(chosenFile)
              load({ file: chosenFile, mapping: null })
            }} />
          <Button asChild variant="outline" loading={preview.isPending}>
            <label htmlFor="statement-file" className="cursor-pointer"><FileUp />{file ? file.name : 'انتخاب فایل CSV'}</label>
          </Button>
        </div>
      </div>
      {!data && !error ? (
        <p className="text-xs text-muted-foreground">
          صورت‌حساب را از اینترنت‌بانک به‌صورت CSV بگیرید (در Excel: ذخیره با قالب CSV UTF-8). ستون‌های تاریخ، شرح، برداشت و واریز معمولاً خودکار شناخته می‌شوند؛ تاریخ شمسی یا میلادی و مبالغ ریالی یا تومانی هر دو پشتیبانی می‌شوند.
        </p>
      ) : null}
      {error ? <Alert variant="destructive"><AlertTriangle />{error}</Alert> : null}

      {data && mapping ? (
        <>
          <fieldset className="grid gap-3 rounded-xl border p-3">
            <legend className="px-1 text-sm font-medium">ستون‌ها</legend>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
              {ROLES.map(({ role, label }) => (
                <FormField key={role} label={label}>
                  <Select value={mapping[role] === null ? NONE : String(mapping[role])} onValueChange={(v) => v && setRole(role, v === NONE ? null : Number(v))}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value={NONE}>—</SelectItem>
                      {Array.from({ length: columns }, (_, i) => <SelectItem key={i} value={String(i)}>{columnLabel(i)}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </FormField>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
              <Segmented<PriceUnit> ariaLabel="واحد مبالغ" size="sm" value={mapping.unit} onChange={(unit) => load({ mapping: { ...mapping, unit } })}
                options={[{ value: 'RIAL', label: 'ریال' }, { value: 'TOMAN', label: 'تومان' }]} />
              <Segmented<DateStyle> ariaLabel="تقویم تاریخ‌ها" size="sm" value={mapping.dateStyle} onChange={(dateStyle) => load({ mapping: { ...mapping, dateStyle } })}
                options={[{ value: 'AUTO', label: 'تشخیص تقویم' }, { value: 'JALALI', label: 'شمسی' }, { value: 'GREGORIAN', label: 'میلادی' }]} />
              <label className="flex items-center gap-2 text-sm">
                <Switch checked={mapping.hasHeader} onCheckedChange={(hasHeader) => load({ mapping: { ...mapping, hasHeader } })} />
                سطر اول عنوان ستون‌هاست
              </label>
            </div>
          </fieldset>

          <div className="overflow-x-auto rounded-xl border">
            <table className="w-full text-sm sm:min-w-[40rem]">
              <thead className="bg-muted/60 text-xs text-muted-foreground">
                <tr>
                  <th className="w-10 p-2">
                    <Checkbox aria-label="انتخاب همه" checked={selected.size > 0 && selected.size === preselect(data).size}
                      onCheckedChange={(v) => setSelected(v ? preselect(data) : new Set())} />
                  </th>
                  <th className="p-2 text-start font-medium">تاریخ</th>
                  <th className="p-2 text-start font-medium">شرح</th>
                  <th className="hidden p-2 text-start font-medium sm:table-cell">دسته‌ی پیشنهادی</th>
                  <th className="p-2 text-end font-medium">مبلغ</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.line} className={cn('border-t', r.error && 'bg-destructive/5', !selected.has(r.line) && !r.error && 'text-muted-foreground')}>
                    <td className="p-2 text-center">
                      <Checkbox aria-label={`ردیف ${f.number(r.line)}`} disabled={!!r.error} checked={selected.has(r.line)} onCheckedChange={(v) => toggle(r, !!v)} />
                    </td>
                    <td className="whitespace-nowrap p-2">{r.date ? f.dateShort(r.date) : '—'}</td>
                    <td className="p-2">
                      <span className="line-clamp-2">{r.description ?? '—'}</span>
                      {r.categoryId ? <span className="block text-xs text-muted-foreground sm:hidden">{categoryNames.get(r.categoryId)}</span> : null}
                      {r.error ? <span className="block text-xs text-destructive">ردیف {f.number(r.line)}: {r.error}</span> : null}
                      {r.duplicate ? <Badge variant="warning" className="mt-1">احتمالاً تکراری</Badge> : null}
                    </td>
                    <td className="hidden p-2 text-xs sm:table-cell">{r.categoryId ? categoryNames.get(r.categoryId) : '—'}</td>
                    <td className="p-2 text-end">
                      {r.amount ? <Amount value={r.amount.replace(/^-/, '')} tone={r.amount.startsWith('-') ? 'expense' : 'income'} sign={r.amount.startsWith('-') ? '-' : '+'} /> : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.rows.length > rows.length ? (
            <Button variant="outline" className="w-fit justify-self-center" onClick={() => setShowAll(true)}>نمایش {f.number(data.rows.length - rows.length)} ردیف دیگر</Button>
          ) : null}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-muted/50 p-3 text-sm">
            <p>
              {f.number(chosen.length)} از {f.number(data.rows.length)} ردیف انتخاب شده
              {chosen.length ? <> | واریز <Amount value={inflow} tone="income" /> | برداشت <Amount value={outflow} tone="expense" /></> : null}
            </p>
            <Button disabled={chosen.length === 0} loading={commit.isPending} onClick={record}>ثبت {f.number(chosen.length)} تراکنش</Button>
          </div>
        </>
      ) : null}
    </div>
  )
}
