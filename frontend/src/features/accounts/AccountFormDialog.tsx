import { useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input, Textarea } from '@/components/ui/input'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { CommoditySelect } from '@/components/finance/selects'
import { usePrefs } from '@/app/preferences'
import { useCommodityMap } from '@/features/commodities/api'
import type { Account, AccountType } from '@/lib/api/types'
import { fromDisplayAmount } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { ACCOUNT_GROUPS, ACCOUNT_TYPE_LABELS, BANKS, DEFAULT_COMMODITY } from '@/lib/labels'
import { useSaveAccount } from './api'

const NO_BANK = '__none__'
const BANK_TYPES: AccountType[] = ['BANK', 'LOAN', 'CREDIT']

export function AccountFormDialog({ open, onOpenChange, account, defaultType }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  account?: Account
  defaultType?: AccountType
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? <AccountForm account={account} defaultType={defaultType} onDone={() => onOpenChange(false)} /> : null}
    </Dialog>
  )
}

function AccountForm({ account, defaultType, onDone }: { account?: Account; defaultType?: AccountType; onDone: () => void }) {
  const prefs = usePrefs()
  const commodities = useCommodityMap()
  const save = useSaveAccount()
  const [type, setType] = useState<AccountType>(account?.type ?? defaultType ?? 'BANK')
  const [name, setName] = useState(account?.name ?? '')
  const [commodity, setCommodity] = useState(account?.commodity ?? DEFAULT_COMMODITY[defaultType ?? 'BANK'])
  const [bank, setBank] = useState<string | null>(account?.bank ?? null)
  const [hints, setHints] = useState((account?.identifierHints ?? []).join('، '))
  const [counterparty, setCounterparty] = useState(account?.counterparty ?? '')
  const [includeInNetWorth, setInclude] = useState(account?.includeInNetWorth ?? true)
  const [notes, setNotes] = useState(account?.notes ?? '')
  const [opening, setOpening] = useState('')
  const [openingDate, setOpeningDate] = useState(todayIso())
  const [error, setError] = useState<string | null>(null)
  const liability = ['LOAN', 'DEBT', 'CREDIT'].includes(type)

  const changeType = (t: AccountType) => {
    setType(t)
    if (!account) setCommodity(DEFAULT_COMMODITY[t])
  }

  const submit = () => {
    setError(null)
    if (!name.trim()) return setError('نام حساب را وارد کنید.')
    save.mutate(
      {
        id: account?.id,
        name,
        type,
        commodity,
        bank: BANK_TYPES.includes(type) ? bank : null,
        identifierHints: hints.split(/[،,\s]+/).map((h) => h.trim()).filter(Boolean),
        counterparty: type === 'DEBT' || type === 'RECEIVABLE' ? counterparty || null : null,
        includeInNetWorth,
        notes: notes || null,
        openingBalance: account || !opening ? null : fromDisplayAmount(opening, commodity, prefs),
        openingDate: account ? null : openingDate,
      },
      {
        onSuccess: () => {
          toast.success(account ? 'حساب ذخیره شد.' : 'حساب ساخته شد.')
          onDone()
        },
        onError: (e) => setError(e.message),
      },
    )
  }

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{account ? 'ویرایش حساب' : 'حساب جدید'}</DialogTitle>
        <DialogDescription>
          {account ? 'نوع و واحد حساب بعد از ثبت تراکنش‌ها قابل تغییر اساسی نیست.' : 'هر حساب یک واحد دارد: تومان، ارز، طلا، سکه یا رمزارز.'}
        </DialogDescription>
      </DialogHeader>
      <DialogBody>
        <form id="account-form" className="grid gap-4" onSubmit={(e) => { e.preventDefault(); submit() }} noValidate>
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <FormField label="نوع حساب">
            <Select value={type} onValueChange={(v) => changeType(v as AccountType)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {ACCOUNT_GROUPS.map((g) => (
                  <SelectGroup key={g.title}>
                    <SelectLabel>{g.title}</SelectLabel>
                    {g.types.map((t) => <SelectItem key={t} value={t}>{ACCOUNT_TYPE_LABELS[t]}</SelectItem>)}
                  </SelectGroup>
                ))}
              </SelectContent>
            </Select>
          </FormField>
          <FormField label="نام">
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100}
              placeholder={type === 'BANK' ? 'مثلاً ملت حقوق' : type === 'GOLD' ? 'مثلاً سکه‌های امامی' : liability ? 'مثلاً وام خرید مسکن' : 'نام حساب'} />
          </FormField>
          <FormField label="واحد" hint={account ? 'واحد حساب بعد از ساخت قابل تغییر نیست.' : undefined}>
            <CommoditySelect value={commodity} onChange={setCommodity} disabled={!!account} />
          </FormField>
          {BANK_TYPES.includes(type) ? (
            <FormField label="بانک" optional>
              <Select value={bank ?? NO_BANK} onValueChange={(v) => setBank(v === NO_BANK ? null : v)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_BANK}>انتخاب نشده</SelectItem>
                  {BANKS.map((b) => <SelectItem key={b.code} value={b.code}>{b.name}</SelectItem>)}
                </SelectContent>
              </Select>
            </FormField>
          ) : null}
          {type === 'BANK' || type === 'EWALLET' ? (
            <FormField label="چهار رقم آخر کارت یا حساب" optional hint="برای تشخیص خودکار حساب در پیامک‌های بانک؛ چند مورد را با ویرگول جدا کنید.">
              <Input value={hints} onChange={(e) => setHints(e.target.value)} inputMode="numeric" className="ltr text-start" placeholder="1234" />
            </FormField>
          ) : null}
          {type === 'DEBT' || type === 'RECEIVABLE' ? (
            <FormField label={type === 'DEBT' ? 'بدهکار به' : 'طلبکار از'} optional>
              <Input value={counterparty} onChange={(e) => setCounterparty(e.target.value)} maxLength={100} placeholder="نام شخص" />
            </FormField>
          ) : null}
          {!account ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label={liability ? 'مانده‌ی بدهی فعلی' : 'موجودی فعلی'} optional>
                <MoneyInput value={opening} onChange={setOpening} commodity={commodities.get(commodity)} allowNegative={!liability} />
              </FormField>
              <FormField label="تاریخ موجودی">
                <JalaliDatePicker value={openingDate} onChange={setOpeningDate} />
              </FormField>
            </div>
          ) : null}
          <label className="flex items-center justify-between gap-4 rounded-lg border p-3">
            <span>
              <span className="block text-sm font-medium">محاسبه در دارایی خالص</span>
              <span className="block text-xs text-muted-foreground">مثلاً برای پولِ امانیِ دیگران خاموش کنید.</span>
            </span>
            <Switch checked={includeInNetWorth} onCheckedChange={setInclude} />
          </label>
          <FormField label="یادداشت" optional>
            <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={2000} />
          </FormField>
        </form>
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={onDone}>انصراف</Button>
        <Button type="submit" form="account-form" loading={save.isPending}>{account ? 'ذخیره' : 'ساخت حساب'}</Button>
      </DialogFooter>
    </DialogContent>
  )
}
