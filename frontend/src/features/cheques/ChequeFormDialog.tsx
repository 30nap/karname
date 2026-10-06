import { useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input, Textarea } from '@/components/ui/input'
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { AccountSelect, CategorySelect } from '@/components/finance/selects'
import { usePrefs } from '@/app/preferences'
import { isTomanAsset } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { Cheque, ChequeDirection } from '@/lib/api/types'
import { fromDisplayAmount, IRT, toDisplayAmount } from '@/lib/format/money'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { BANKS } from '@/lib/labels'
import { toLatinDigits, toPersianDigits } from '@/lib/persian/digits'
import { useSaveCheque } from './api'

const NO_BANK = '__none__'
type Booking = 'category' | 'account'

function ChequeForm({ cheque, direction: initialDirection, onClose }: { cheque?: Cheque; direction: ChequeDirection; onClose: () => void }) {
  const prefs = usePrefs()
  const save = useSaveCheque()
  const [direction, setDirection] = useState<ChequeDirection>(cheque?.direction ?? initialDirection)
  const [amount, setAmount] = useState(cheque ? toDisplayAmount(cheque.amount, 'IRT', prefs) : '')
  const [counterparty, setCounterparty] = useState(cheque?.counterparty ?? '')
  const [dueDate, setDueDate] = useState(cheque?.dueDate ?? addDaysIso(todayIso(), 30))
  const [hasIssueDate, setHasIssueDate] = useState(!!cheque?.issueDate)
  const [issueDate, setIssueDate] = useState(cheque?.issueDate ?? todayIso())
  const [accountId, setAccountId] = useState<number | null>(cheque?.accountId ?? null)
  const [booking, setBooking] = useState<Booking>(cheque?.counterAccountId ? 'account' : 'category')
  const [categoryId, setCategoryId] = useState<number | null>(cheque?.categoryId ?? null)
  const [counterAccountId, setCounterAccountId] = useState<number | null>(cheque?.counterAccountId ?? null)
  const [bank, setBank] = useState<string | null>(cheque?.bank ?? null)
  const [sayadId, setSayadId] = useState(cheque?.sayadId ?? '')
  const [serial, setSerial] = useState(cheque?.serial ?? '')
  const [description, setDescription] = useState(cheque?.description ?? '')
  const [notes, setNotes] = useState(cheque?.notes ?? '')
  const [error, setError] = useState<string | null>(null)
  const issued = direction === 'ISSUED'
  const sayadDigits = toLatinDigits(sayadId).replace(/\D/g, '')

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!amount || Number(amount) <= 0) return setError('مبلغ چک را وارد کنید.')
    if (sayadDigits && sayadDigits.length !== 16) return setError('شناسه‌ی صیادی ۱۶ رقم است.')
    save.mutate({
      id: cheque?.id,
      direction,
      amount: fromDisplayAmount(amount, 'IRT', prefs),
      counterparty: counterparty.trim() || null,
      dueDate,
      issueDate: hasIssueDate ? issueDate : null,
      accountId,
      counterAccountId: booking === 'account' ? counterAccountId : null,
      categoryId: booking === 'category' ? categoryId : null,
      bank,
      sayadId: sayadDigits || null,
      serial: serial.trim() || null,
      description: description.trim() || null,
      notes: notes.trim() || null,
    }, {
      onSuccess: () => {
        toast.success(cheque ? 'چک ویرایش شد.' : 'چک ثبت شد.')
        onClose()
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{cheque ? 'ویرایش چک' : 'چک جدید'}</DialogTitle>
          <DialogDescription>تا وقتی چک پاس نشده فقط سررسیدش یادآوری و در پیش‌بینی نقدینگی حساب می‌شود؛ با پاس شدن، تراکنشش ثبت می‌شود.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          {cheque ? null : (
            <Segmented<ChequeDirection> ariaLabel="نوع چک" value={direction} onChange={(d) => { setDirection(d); setCategoryId(null) }}
              options={[{ value: 'ISSUED', label: 'چک صادره (پرداختی)', className: 'text-expense' }, { value: 'RECEIVED', label: 'چک دریافتی', className: 'text-income' }]} />
          )}
          <FormField label="مبلغ">
            <MoneyInput value={amount} onChange={setAmount} commodity={IRT} showQuickButtons />
          </FormField>
          <FormField label={issued ? 'در وجه' : 'از طرف'} optional>
            <Input value={counterparty} onChange={(e) => setCounterparty(e.target.value)} maxLength={100} placeholder={issued ? 'مثلاً صاحب‌خانه' : 'مثلاً شرکت الف'} />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="تاریخ سررسید">
              <JalaliDatePicker value={dueDate} onChange={setDueDate} />
            </FormField>
            <div className="grid content-start gap-2">
              <label className="flex items-center justify-between gap-3 text-sm font-medium">
                تاریخ صدور
                <Switch checked={hasIssueDate} onCheckedChange={setHasIssueDate} aria-label="تاریخ صدور دارد" />
              </label>
              {hasIssueDate ? <JalaliDatePicker value={issueDate} onChange={setIssueDate} aria-label="تاریخ صدور" /> : null}
            </div>
          </div>
          <FormField label={issued ? 'از حساب جاری' : 'واریز به حساب'} optional hint={issued ? undefined : 'می‌توانید هنگام وصول هم انتخاب کنید.'}>
            <AccountSelect value={accountId} onChange={setAccountId} filter={isTomanAsset} />
          </FormField>
          <div className="grid gap-2">
            <p className="text-sm font-medium">ثبت در حساب‌ها</p>
            <Segmented<Booking> ariaLabel="نوع ثبت" value={booking} onChange={setBooking}
              options={[{ value: 'category', label: issued ? 'هزینه' : 'درآمد' }, { value: 'account', label: issued ? 'پرداخت بدهی' : 'وصول طلب' }]} />
            {booking === 'category' ? (
              <FormField label="دسته‌بندی" optional>
                <CategorySelect kind={issued ? 'EXPENSE' : 'INCOME'} value={categoryId} onChange={setCategoryId} />
              </FormField>
            ) : (
              <FormField label={issued ? 'حساب بدهی' : 'حساب طلب'} hint={`با پاس شدن چک، مبلغش از ${issued ? 'این بدهی' : 'این طلب'} کم می‌شود.`}>
                <AccountSelect value={counterAccountId} onChange={setCounterAccountId} exclude={accountId}
                  filter={(a) => a.commodity === 'IRT'} placeholder={issued ? 'مثلاً بدهی به فروشنده' : 'مثلاً طلب از مشتری'} />
              </FormField>
            )}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="شناسه‌ی صیادی" optional hint="۱۶ رقم؛ روی چک‌های جدید">
              <Input value={prefs.digits === 'PERSIAN' ? toPersianDigits(sayadId) : sayadId} onChange={(e) => setSayadId(toLatinDigits(e.target.value).replace(/[^\d\s-]/g, ''))}
                inputMode="numeric" dir="ltr" className="text-start" maxLength={19} />
            </FormField>
            <FormField label="سریال" optional>
              <Input value={serial} onChange={(e) => setSerial(e.target.value)} maxLength={30} dir="ltr" className="text-start" />
            </FormField>
          </div>
          <FormField label="بانک" optional>
            <Select value={bank ?? NO_BANK} onValueChange={(v) => setBank(v === NO_BANK ? null : v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_BANK}>— نامشخص —</SelectItem>
                {BANKS.map((b) => <SelectItem key={b.code} value={b.code}>{b.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </FormField>
          <FormField label="شرح تراکنش" optional hint={`اگر خالی باشد: «${issued ? 'چک صادره به' : 'چک دریافتی از'} ${counterparty.trim() || '…'}»`}>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} />
          </FormField>
          <FormField label="یادداشت" optional>
            <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={2000} />
          </FormField>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={save.isPending}>ذخیره</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

export function ChequeFormDialog({ open, onOpenChange, cheque, direction = 'ISSUED' }: {
  open: boolean; onOpenChange: (open: boolean) => void; cheque?: Cheque; direction?: ChequeDirection
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? <ChequeForm key={cheque?.id ?? `new-${direction}`} cheque={cheque} direction={direction} onClose={() => onOpenChange(false)} /> : null}
    </Dialog>
  )
}
