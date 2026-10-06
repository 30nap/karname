import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input, Textarea } from '@/components/ui/input'
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Amount } from '@/components/finance/Amount'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { AccountSelect } from '@/components/finance/selects'
import { usePrefs, useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { everydayAccount, isTomanAsset } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { Loan, LoanMethod } from '@/lib/api/types'
import { fromDisplayAmount, IRT, toDisplayAmount } from '@/lib/format/money'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { BANKS, LOAN_METHOD_LABELS } from '@/lib/labels'
import { toLatinDigits } from '@/lib/persian/digits'
import { useLoanPreview, useSaveLoan } from './api'

const NO_BANK = '__none__'

/** Debounces a value so the schedule preview isn't requested on every keystroke. */
function useDebounced<T>(value: T, delay = 400): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(handle)
  }, [value, delay])
  return debounced
}

const digitsOnly = (raw: string, decimals = false) => {
  const latin = toLatinDigits(raw).replace(/[٫/]/g, '.')
  return decimals ? latin.replace(/[^\d.]/g, '') : latin.replace(/\D/g, '')
}

function LoanForm({ loan, onClose }: { loan?: Loan; onClose: () => void }) {
  const prefs = usePrefs()
  const f = useFormat()
  const navigate = useNavigate()
  const save = useSaveLoan()
  const [name, setName] = useState(loan?.name ?? '')
  const [bank, setBank] = useState<string | null>(loan?.bank ?? null)
  const [principal, setPrincipal] = useState(loan ? toDisplayAmount(loan.principal, 'IRT', prefs) : '')
  const [rate, setRate] = useState(loan?.annualRate ?? '18')
  const [months, setMonths] = useState(loan ? String(loan.termMonths) : '36')
  const [firstDue, setFirstDue] = useState(loan?.firstDueDate ?? addDaysIso(todayIso(), 30))
  const [method, setMethod] = useState<LoanMethod>(loan?.method ?? 'ANNUITY')
  const [installment, setInstallment] = useState(loan?.installmentAmount ? toDisplayAmount(loan.installmentAmount, 'IRT', prefs) : '')
  const { data: accounts = [] } = useAccounts()
  const fallbackAccount = loan ? null : everydayAccount(accounts)?.id ?? null
  const [paymentChoice, setPaymentAccountId] = useState<number | null | undefined>(loan ? loan.paymentAccountId : undefined)
  const paymentAccountId = paymentChoice === undefined ? fallbackAccount : paymentChoice
  const [start, setStart] = useState<'NEW' | 'EXISTING'>('NEW')
  const [depositChoice, setDepositAccountId] = useState<number | null>(null)
  const depositAccountId = depositChoice ?? fallbackAccount
  const [receivedOn, setReceivedOn] = useState(todayIso())
  const [paidBefore, setPaidBefore] = useState(loan ? String(loan.paidBefore) : '0')
  const [notes, setNotes] = useState(loan?.notes ?? '')
  const [error, setError] = useState<string | null>(null)

  const principalToman = principal ? fromDisplayAmount(principal, 'IRT', prefs) : ''
  const installmentToman = installment ? fromDisplayAmount(installment, 'IRT', prefs) : null
  const previewParams = useDebounced(
    principalToman && Number(principalToman) > 0 && rate !== '' && Number(months) > 0 && firstDue
      ? { principal: principalToman, annualRate: rate, termMonths: Number(months), method, installmentAmount: installmentToman, firstDueDate: firstDue }
      : null,
  )
  const preview = useLoanPreview(previewParams)
  const previewError = preview.error instanceof ApiError ? preview.error.message : null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!name.trim()) return setError('نام وام را وارد کنید.')
    if (!principalToman) return setError('مبلغ وام را وارد کنید.')
    save.mutate({
      id: loan?.id,
      name: name.trim(),
      bank,
      principal: principalToman,
      annualRate: rate || '0',
      termMonths: Number(months),
      firstDueDate: firstDue,
      method,
      installmentAmount: installmentToman,
      paymentAccountId,
      notes: notes || null,
      start: loan ? undefined : start,
      depositAccountId: !loan && start === 'NEW' ? depositAccountId : null,
      receivedOn: !loan && start === 'NEW' ? receivedOn : null,
      paidBefore: loan || start === 'EXISTING' ? Number(paidBefore || 0) : 0,
    }, {
      onSuccess: (saved) => {
        toast.success(loan ? 'وام ویرایش شد.' : 'وام ثبت شد.')
        onClose()
        if (!loan) navigate(`/loans/${saved.id}`)
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{loan ? 'ویرایش وام' : 'وام جدید'}</DialogTitle>
          <DialogDescription>جدول اقساط از روی مبلغ، نرخ و تعداد اقساط ساخته می‌شود؛ اگر بانک مبلغ قسط را دقیق اعلام کرده، آن را هم وارد کنید.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="نام">
              <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="مثلاً وام خرید خودرو" />
            </FormField>
            <FormField label="بانک" optional>
              <Select value={bank ?? NO_BANK} onValueChange={(v) => setBank(v === NO_BANK ? null : v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_BANK}>— بدون بانک —</SelectItem>
                  {BANKS.map((b) => <SelectItem key={b.code} value={b.code}>{b.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </FormField>
          </div>
          <FormField label="مبلغ وام">
            <MoneyInput value={principal} onChange={setPrincipal} commodity={IRT} showQuickButtons />
          </FormField>
          <div className="grid grid-cols-2 gap-4">
            <FormField label="نرخ سود سالانه (٪)" hint="قرض‌الحسنه: کارمزد، مثلاً ۴">
              <Input value={f.digits(rate)} onChange={(e) => setRate(digitsOnly(e.target.value, true))} inputMode="decimal" dir="ltr" className="text-end" />
            </FormField>
            <FormField label="تعداد اقساط (ماه)">
              <Input value={f.digits(months)} onChange={(e) => setMonths(digitsOnly(e.target.value))} inputMode="numeric" dir="ltr" className="text-end" />
            </FormField>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="سررسید اولین قسط">
              <JalaliDatePicker value={firstDue} onChange={setFirstDue} />
            </FormField>
            <FormField label="روش محاسبه">
              <Segmented<LoanMethod> ariaLabel="روش محاسبه" value={method} onChange={setMethod}
                options={(['ANNUITY', 'EQUAL_PRINCIPAL'] as const).map((m) => ({ value: m, label: LOAN_METHOD_LABELS[m] }))} />
            </FormField>
          </div>
          <FormField label="مبلغ قسط طبق بانک" optional hint="خالی بگذارید تا با فرمول بانکی محاسبه شود.">
            <MoneyInput value={installment} onChange={setInstallment} commodity={IRT} showWords={false} />
          </FormField>
          {preview.data && !previewError ? (
            <div className="grid grid-cols-3 gap-2 rounded-xl bg-muted/60 p-3 text-sm">
              <div><p className="text-xs text-muted-foreground">قسط ماهانه</p><Amount value={preview.data.firstInstallment} className="font-semibold" /></div>
              <div><p className="text-xs text-muted-foreground">مجموع سود</p><Amount value={preview.data.totalInterest} className="font-semibold" /></div>
              <div><p className="text-xs text-muted-foreground">آخرین قسط</p><span className="font-semibold">{f.date(preview.data.endDate)}</span></div>
            </div>
          ) : previewError ? <Alert variant="warning">{previewError}</Alert> : null}
          <FormField label="پرداخت اقساط از حساب" optional>
            <AccountSelect value={paymentAccountId} onChange={setPaymentAccountId} filter={isTomanAsset} />
          </FormField>
          {loan ? (
            <FormField label="اقساط پرداخت‌شده پیش از ثبت در برنامه">
              <Input value={f.digits(paidBefore)} onChange={(e) => setPaidBefore(digitsOnly(e.target.value))} inputMode="numeric" dir="ltr" className="text-end" />
            </FormField>
          ) : (
            <div className="grid gap-3 rounded-xl border p-3">
              <Segmented<'NEW' | 'EXISTING'> ariaLabel="وضعیت وام" value={start} onChange={setStart}
                options={[{ value: 'NEW', label: 'تازه دریافت کرده‌ام' }, { value: 'EXISTING', label: 'در حال پرداختش هستم' }]} />
              {start === 'NEW' ? (
                <div className="grid gap-4 sm:grid-cols-2">
                  <FormField label="واریز به حساب">
                    <AccountSelect value={depositAccountId} onChange={setDepositAccountId} filter={isTomanAsset} />
                  </FormField>
                  <FormField label="تاریخ دریافت">
                    <JalaliDatePicker value={receivedOn} onChange={setReceivedOn} />
                  </FormField>
                </div>
              ) : (
                <FormField label="چند قسط تا امروز پرداخت شده؟" hint="مانده‌ی وام از روی جدول اقساط محاسبه می‌شود.">
                  <Input value={f.digits(paidBefore)} onChange={(e) => setPaidBefore(digitsOnly(e.target.value))} inputMode="numeric" dir="ltr" className="text-end" />
                </FormField>
              )}
            </div>
          )}
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

export function LoanFormDialog({ open, onOpenChange, loan }: { open: boolean; onOpenChange: (open: boolean) => void; loan?: Loan }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? <LoanForm loan={loan} onClose={() => onOpenChange(false)} /> : null}
    </Dialog>
  )
}
