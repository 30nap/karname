import { ArchiveRestore, DatabaseBackup, FileDown } from 'lucide-react'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { PageHeader } from '@/components/ui/page-header'
import { Segmented } from '@/components/ui/segmented'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { useFormat } from '@/app/preferences'
import { API_BASE, ApiError, query } from '@/lib/api/client'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { useRestoreBackup } from './api'
import { ImportWizard } from './ImportWizard'

/** The word typed to confirm that a restore replaces everything. */
const CONFIRM_WORD = 'جایگزینی'

function ExportCard() {
  const [range, setRange] = useState<'all' | 'custom'>('all')
  const [from, setFrom] = useState(addDaysIso(todayIso(), -365))
  const [to, setTo] = useState(todayIso())
  const csv = `${API_BASE}/io/transactions.csv${range === 'custom' ? query({ from, to }) : ''}`
  return (
    <Card>
      <CardHeader>
        <CardTitle>خروجی گرفتن</CardTitle>
        <CardDescription>تراکنش‌ها برای Excel، یا پشتیبان کامل همه‌ی اطلاعات برای نگه‌داری و انتقال.</CardDescription>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-4">
        <div className="grid gap-3 rounded-xl border p-3">
          <p className="text-sm font-medium">تراکنش‌ها (CSV)</p>
          <Segmented<'all' | 'custom'> ariaLabel="بازه‌ی خروجی" size="sm" value={range} onChange={setRange} className="w-fit"
            options={[{ value: 'all', label: 'همه‌ی تاریخ‌ها' }, { value: 'custom', label: 'بازه‌ی دلخواه' }]} />
          {range === 'custom' ? (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <FormField label="از"><JalaliDatePicker value={from} onChange={setFrom} /></FormField>
              <FormField label="تا"><JalaliDatePicker value={to} onChange={setTo} /></FormField>
            </div>
          ) : null}
          <p className="text-xs text-muted-foreground">با تاریخ شمسی و میلادی، مبلغ دقیق در واحد هر حساب، دسته و شرح؛ در Excel فارسی درست نمایش داده می‌شود.</p>
          <Button asChild variant="outline" className="w-fit"><a href={csv} download><FileDown />دانلود CSV</a></Button>
        </div>
        <div className="grid gap-3 rounded-xl border p-3">
          <p className="text-sm font-medium">پشتیبان کامل (JSON)</p>
          <p className="text-xs text-muted-foreground">
            حساب‌ها، تراکنش‌ها، دسته‌ها، بودجه، اهداف، وام‌ها، چک‌ها، تراکنش‌های تکراری، قیمت‌های خودتان و تنظیمات. با «بازگردانی» در همین صفحه، در این کارنامه یا نصب دیگری برمی‌گردد.
          </p>
          <Button asChild variant="outline" className="w-fit"><a href={`${API_BASE}/io/backup`} download><DatabaseBackup />دانلود پشتیبان</a></Button>
        </div>
      </CardContent>
    </Card>
  )
}

function RestoreCard() {
  const f = useFormat()
  const navigate = useNavigate()
  const restore = useRestoreBackup()
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [typed, setTyped] = useState('')
  const [error, setError] = useState<string | null>(null)
  const close = () => {
    setFile(null)
    setTyped('')
    setError(null)
    if (input.current) input.current.value = ''
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle>بازگردانی پشتیبان</CardTitle>
        <CardDescription>همه‌ی اطلاعات فعلی شما پاک و با محتوای فایل پشتیبان جایگزین می‌شود.</CardDescription>
      </CardHeader>
      <CardContent>
        <input ref={input} id="backup-file" type="file" accept=".json,application/json" className="sr-only" aria-label="فایل پشتیبان"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        <Button asChild variant="outline"><label htmlFor="backup-file" className="cursor-pointer"><ArchiveRestore />انتخاب فایل پشتیبان</label></Button>
      </CardContent>
      <Dialog open={file !== null} onOpenChange={(o) => !o && close()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>بازگردانی «{file?.name}»</DialogTitle>
            <DialogDescription>
              حساب‌ها، تراکنش‌ها و همه‌ی اطلاعات فعلی‌تان پاک می‌شود و محتوای این فایل جایش را می‌گیرد. اگر فایل خراب باشد، چیزی تغییر نمی‌کند.
            </DialogDescription>
          </DialogHeader>
          <DialogBody className="grid gap-3">
            {error ? <Alert variant="destructive">{error}</Alert> : null}
            <FormField label={`برای تأیید، «${CONFIRM_WORD}» را بنویسید`}>
              <Input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
            </FormField>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={close}>انصراف</Button>
            <Button variant="destructive" disabled={typed.trim() !== CONFIRM_WORD} loading={restore.isPending}
              onClick={() => file && restore.mutate(file, {
                onSuccess: (summary) => {
                  toast.success(`پشتیبان بازگردانده شد: ${f.number(summary.rows.accounts ?? 0)} حساب و ${f.number(summary.rows.transactions ?? 0)} تراکنش.`)
                  close()
                  navigate('/')
                },
                onError: (e) => setError(e instanceof ApiError ? e.message : 'بازگردانی انجام نشد.'),
              })}>
              جایگزینی اطلاعات
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

export function DataPage() {
  return (
    <>
      <PageHeader title="ورود و خروج داده" description="خروجی Excel، پشتیبان کامل، و ورود صورت‌حساب بانک" />
      <div className="grid grid-cols-1 gap-5">
        <Card>
          <CardHeader>
            <CardTitle>ورود صورت‌حساب بانک</CardTitle>
            <CardDescription>تراکنش‌ها پیش از ثبت نمایش داده می‌شوند؛ ردیف‌های تکراری و ثبت‌شده کنار گذاشته می‌شوند و دسته‌ها از روی سابقه‌ی شما پیشنهاد می‌شوند.</CardDescription>
          </CardHeader>
          <CardContent><ImportWizard /></CardContent>
        </Card>
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-2">
          <ExportCard />
          <RestoreCard />
        </div>
      </div>
    </>
  )
}
