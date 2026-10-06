import { useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { AccountSelect, CategorySelect } from '@/components/finance/selects'
import { usePrefs, useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCommodityMap } from '@/features/commodities/api'
import { everydayAccount } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { Frequency, RecurringMode, RecurringRule } from '@/lib/api/types'
import { fromDisplayAmount, toDisplayAmount } from '@/lib/format/money'
import { addDaysIso, JALALI_MONTHS, jalaliWeekdayIndex, toJalali, todayIso, WEEKDAYS } from '@/lib/jalali'
import { FREQUENCY_LABELS } from '@/lib/labels'
import { useSaveRule } from './api'

type RuleType = RecurringRule['type']

const UNIT: Record<Frequency, string> = { WEEKLY: 'هفته', MONTHLY: 'ماه', YEARLY: 'سال' }

function RuleForm({ rule, onClose }: { rule?: RecurringRule; onClose: () => void }) {
  const prefs = usePrefs()
  const f = useFormat()
  const commodities = useCommodityMap()
  const { data: accounts = [] } = useAccounts()
  const save = useSaveRule()
  const today = todayIso()
  const start0 = rule?.startDate ?? today
  const jStart = toJalali(start0)

  const [name, setName] = useState(rule?.name ?? '')
  const [type, setType] = useState<RuleType>(rule?.type ?? 'EXPENSE')
  const [accountChoice, setAccountId] = useState<number | null>(rule?.accountId ?? null)
  const accountId = accountChoice ?? (rule ? null : everydayAccount(accounts)?.id ?? null)
  const [toAccountId, setToAccountId] = useState<number | null>(rule?.toAccountId ?? null)
  const [categoryId, setCategoryId] = useState<number | null>(rule?.categoryId ?? null)
  const account = accounts.find((a) => a.id === accountId)
  const toAccount = accounts.find((a) => a.id === toAccountId)
  const [amount, setAmount] = useState(rule ? toDisplayAmount(rule.amount, accounts.find((a) => a.id === rule.accountId)?.commodity ?? 'IRT', prefs) : '')
  const [toAmount, setToAmount] = useState(rule?.toAmount && rule.toAccountId
    ? toDisplayAmount(rule.toAmount, accounts.find((a) => a.id === rule.toAccountId)?.commodity ?? 'IRT', prefs) : '')
  const [description, setDescription] = useState(rule?.description ?? '')
  const [frequency, setFrequency] = useState<Frequency>(rule?.frequency ?? 'MONTHLY')
  const [every, setEvery] = useState(rule?.interval ?? 1)
  const [dayOfMonth, setDayOfMonth] = useState(rule?.dayOfMonth ?? jStart.day)
  const [dayOfWeek, setDayOfWeek] = useState(rule?.dayOfWeek ?? jalaliWeekdayIndex(start0))
  const [monthOfYear, setMonthOfYear] = useState(rule?.monthOfYear ?? jStart.month)
  const [startDate, setStartDate] = useState(start0)
  const [hasEnd, setHasEnd] = useState(!!rule?.endDate)
  const [endDate, setEndDate] = useState(rule?.endDate ?? addDaysIso(start0, 365))
  const [mode, setMode] = useState<RecurringMode>(rule?.mode ?? 'REMIND')
  const [error, setError] = useState<string | null>(null)

  const commodity = commodities.get(account?.commodity ?? 'IRT')
  const toCommodity = commodities.get(toAccount?.commodity ?? 'IRT')
  const exchange = type === 'TRANSFER' && !!account && !!toAccount && account.commodity !== toAccount.commodity
  const n = (v: number) => f.number(v)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!name.trim()) return setError('یک نام برای این تراکنش تکراری بنویسید؛ مثلاً «اجاره‌ی خانه».')
    if (!account) return setError('حساب را انتخاب کنید.')
    if (!amount || Number(amount) <= 0) return setError('مبلغ را وارد کنید.')
    if (type === 'TRANSFER' && !toAccount) return setError('حساب مقصد را انتخاب کنید.')
    if (exchange && !toAmount) return setError('مقدار دریافتی در حساب مقصد را وارد کنید.')
    save.mutate({
      id: rule?.id,
      name: name.trim(),
      type,
      accountId: account.id,
      toAccountId: type === 'TRANSFER' ? toAccount!.id : null,
      amount: fromDisplayAmount(amount, account.commodity, prefs),
      toAmount: exchange ? fromDisplayAmount(toAmount, toAccount!.commodity, prefs) : null,
      categoryId: type === 'TRANSFER' ? null : categoryId,
      description: description.trim() || null,
      frequency,
      interval: every,
      dayOfMonth: frequency === 'WEEKLY' ? null : dayOfMonth,
      dayOfWeek: frequency === 'WEEKLY' ? dayOfWeek : null,
      monthOfYear: frequency === 'YEARLY' ? monthOfYear : null,
      startDate,
      endDate: hasEnd ? endDate : null,
      mode,
      active: rule?.active ?? true,
    }, {
      onSuccess: () => {
        toast.success(rule ? 'تراکنش تکراری ویرایش شد.' : 'تراکنش تکراری ساخته شد.')
        onClose()
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
    })
  }

  const daySelect = (
    <Select value={String(dayOfMonth)} onValueChange={(v) => setDayOfMonth(Number(v))}>
      <SelectTrigger aria-label="روز ماه"><SelectValue /></SelectTrigger>
      <SelectContent>
        {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => <SelectItem key={d} value={String(d)}>{n(d)}</SelectItem>)}
      </SelectContent>
    </Select>
  )

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{rule ? 'ویرایش تراکنش تکراری' : 'تراکنش تکراری جدید'}</DialogTitle>
          <DialogDescription>حقوق، اجاره، اشتراک یا هر پرداختی که مرتب تکرار می‌شود؛ روزها بر اساس تقویم شمسی است.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <Segmented<RuleType>
            ariaLabel="نوع"
            value={type}
            onChange={(t) => { setType(t); setCategoryId(null) }}
            options={[
              { value: 'EXPENSE', label: 'هزینه', className: 'text-expense' },
              { value: 'INCOME', label: 'درآمد', className: 'text-income' },
              { value: 'TRANSFER', label: 'انتقال', className: 'text-transfer' },
            ]}
          />
          <FormField label="نام">
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100}
              placeholder={type === 'INCOME' ? 'مثلاً حقوق' : type === 'TRANSFER' ? 'مثلاً پس‌انداز ماهانه' : 'مثلاً اجاره‌ی خانه'} />
          </FormField>
          <FormField label={exchange ? 'مقدار پرداختی' : 'مبلغ'}>
            <MoneyInput value={amount} onChange={setAmount} commodity={commodity} showQuickButtons />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label={type === 'INCOME' ? 'به حساب' : 'از حساب'}>
              <AccountSelect value={accountId} onChange={setAccountId} />
            </FormField>
            {type === 'TRANSFER' ? (
              <FormField label="به حساب">
                <AccountSelect value={toAccountId} onChange={setToAccountId} exclude={accountId} />
              </FormField>
            ) : (
              <FormField label="دسته‌بندی" optional>
                <CategorySelect kind={type === 'INCOME' ? 'INCOME' : 'EXPENSE'} value={categoryId} onChange={setCategoryId} />
              </FormField>
            )}
          </div>
          {exchange ? (
            <FormField label="مقدار دریافتی">
              <MoneyInput value={toAmount} onChange={setToAmount} commodity={toCommodity} />
            </FormField>
          ) : null}

          <fieldset className="grid gap-3 rounded-xl border p-3">
            <legend className="px-1 text-sm font-medium">زمان‌بندی</legend>
            <Segmented<Frequency> ariaLabel="دوره‌ی تکرار" value={frequency} onChange={setFrequency}
              options={(['WEEKLY', 'MONTHLY', 'YEARLY'] as const).map((v) => ({ value: v, label: FREQUENCY_LABELS[v] }))} />
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="فاصله">
                <Select value={String(every)} onValueChange={(v) => setEvery(Number(v))}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {Array.from({ length: 12 }, (_, i) => i + 1).map((v) => (
                      <SelectItem key={v} value={String(v)}>{v === 1 ? `هر ${UNIT[frequency]}` : `هر ${n(v)} ${UNIT[frequency]}`}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>
              {frequency === 'WEEKLY' ? (
                <FormField label="روز هفته">
                  <Select value={String(dayOfWeek)} onValueChange={(v) => setDayOfWeek(Number(v))}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {WEEKDAYS.map((d, i) => <SelectItem key={d} value={String(i)}>{d}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </FormField>
              ) : frequency === 'MONTHLY' ? (
                <FormField label="روز ماه" hint={dayOfMonth >= 29 ? 'در ماه‌های کوتاه‌تر، آخرین روز ماه.' : undefined}>
                  {daySelect}
                </FormField>
              ) : (
                <FormField label="روز و ماه">
                  <div className="grid grid-cols-[5rem_1fr] gap-2">
                    {daySelect}
                    <Select value={String(monthOfYear)} onValueChange={(v) => setMonthOfYear(Number(v))}>
                      <SelectTrigger aria-label="ماه"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {JALALI_MONTHS.map((m, i) => <SelectItem key={m} value={String(i + 1)}>{m}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  </div>
                </FormField>
              )}
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="از تاریخ">
                <JalaliDatePicker value={startDate} onChange={setStartDate} />
              </FormField>
              <div className="grid content-start gap-2">
                <label className="flex items-center justify-between gap-3 text-sm font-medium">
                  تا تاریخ
                  <Switch checked={hasEnd} onCheckedChange={setHasEnd} aria-label="تاریخ پایان دارد" />
                </label>
                {hasEnd ? <JalaliDatePicker value={endDate} onChange={setEndDate} aria-label="تاریخ پایان" /> : (
                  <p className="text-xs text-muted-foreground">بدون تاریخ پایان</p>
                )}
              </div>
            </div>
          </fieldset>

          <div className="grid gap-2">
            <p className="text-sm font-medium">ثبت هر نوبت</p>
            <Segmented<RecurringMode> ariaLabel="نحوه‌ی ثبت" value={mode} onChange={setMode}
              options={[{ value: 'REMIND', label: 'با یادآوری و تأیید من' }, { value: 'AUTO', label: 'خودکار' }]} />
            <p className="text-xs text-muted-foreground">
              {mode === 'AUTO'
                ? 'هر نوبت در روز سررسیدش خودکار ثبت می‌شود (از امروز به بعد). برای مبالغ ثابت مثل حقوق یا اشتراک مناسب است.'
                : 'نوبت‌ها در فهرست «منتظر ثبت» می‌آیند و با یک کلیک، با همان مبلغ یا مبلغ واقعی، ثبت می‌شوند. برای قبض‌ها و مبالغ متغیر مناسب است.'}
            </p>
          </div>
          <FormField label="شرح تراکنش‌ها" optional hint="اگر خالی باشد، نام بالا شرح تراکنش‌ها می‌شود.">
            <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} />
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

export function RecurringRuleDialog({ open, onOpenChange, rule }: { open: boolean; onOpenChange: (open: boolean) => void; rule?: RecurringRule }) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? <RuleForm key={rule?.id ?? 'new'} rule={rule} onClose={() => onOpenChange(false)} /> : null}
    </Dialog>
  )
}
