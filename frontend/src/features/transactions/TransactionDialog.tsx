import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input, Textarea } from '@/components/ui/input'
import { Segmented } from '@/components/ui/segmented'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { AccountSelect, CategorySelect } from '@/components/finance/selects'
import { usePrefs, useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCommodityMap } from '@/features/commodities/api'
import { suggestCategory } from '@/features/categories/api'
import { ApiError } from '@/lib/api/client'
import type { AccountType, Transaction, TransactionInput, TransactionType } from '@/lib/api/types'
import { fromDisplayAmount, toDisplayAmount } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { TRANSACTION_TYPE_LABELS } from '@/lib/labels'
import Big from 'big.js'
import { useDeleteTransaction, useSaveTransaction } from './api'

type EditableType = 'EXPENSE' | 'INCOME' | 'TRANSFER'

export interface TransactionDraft {
  type?: TransactionType
  accountId?: number | null
  toAccountId?: number | null
  categoryId?: number | null
  amount?: string
  date?: string
  description?: string
}

interface OpenOptions {
  transaction?: Transaction
  draft?: TransactionDraft
}

const DialogContext = createContext<(options?: OpenOptions) => void>(() => {})

// eslint-disable-next-line react-refresh/only-export-components
export function useTransactionDialog() {
  return useContext(DialogContext)
}

const LAST_ACCOUNT_KEY = 'karname.lastAccount'
const EVERYDAY_TYPES: AccountType[] = ['BANK', 'CASH', 'EWALLET']

function readLastAccount(): number | null {
  try {
    const v = localStorage.getItem(LAST_ACCOUNT_KEY)
    return v ? Number(v) : null
  } catch {
    return null
  }
}

/** Provides a single app-wide add/edit transaction dialog. */
export function TransactionDialogProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ open: boolean; options: OpenOptions; key: number }>({ open: false, options: {}, key: 0 })
  const open = useCallback((options: OpenOptions = {}) => setState((s) => ({ open: true, options, key: s.key + 1 })), [])
  return (
    <DialogContext.Provider value={open}>
      {children}
      <Dialog open={state.open} onOpenChange={(o) => setState((s) => ({ ...s, open: o }))}>
        {state.open ? (
          <TransactionForm key={state.key} options={state.options} onDone={() => setState((s) => ({ ...s, open: false }))} />
        ) : null}
      </Dialog>
    </DialogContext.Provider>
  )
}

function TransactionForm({ options, onDone }: { options: OpenOptions; onDone: () => void }) {
  const prefs = usePrefs()
  const f = useFormat()
  const commodities = useCommodityMap()
  const { data: accounts = [] } = useAccounts()
  const save = useSaveTransaction()
  const remove = useDeleteTransaction()
  const editing = options.transaction
  const balanceEntry = editing && (editing.type === 'OPENING' || editing.type === 'ADJUSTMENT')

  const initialAccount = editing?.account.id ?? options.draft?.accountId ?? readLastAccount()
  const [type, setType] = useState<EditableType>(() => {
    const t = editing?.type ?? options.draft?.type
    return t === 'INCOME' || t === 'TRANSFER' ? t : 'EXPENSE'
  })
  const [accountChoice, setAccountId] = useState<number | null>(initialAccount)
  // The remembered account may be gone (deleted or archived): fall back to the first everyday Toman account.
  const accountId = useMemo(() => {
    if (editing || accounts.length === 0) return accountChoice
    if (accountChoice !== null && accounts.some((a) => a.id === accountChoice)) return accountChoice
    return (accounts.find((a) => EVERYDAY_TYPES.includes(a.type) && a.commodity === 'IRT') ?? accounts[0]).id
  }, [editing, accounts, accountChoice])
  const [toAccountId, setToAccountId] = useState<number | null>(editing?.toAccount?.id ?? options.draft?.toAccountId ?? null)
  const [categoryId, setCategoryId] = useState<number | null>(editing?.category?.id ?? options.draft?.categoryId ?? null)
  const [date, setDate] = useState(editing?.date ?? options.draft?.date ?? todayIso())
  const [description, setDescription] = useState(editing?.description ?? options.draft?.description ?? '')
  const [notes, setNotes] = useState(editing?.notes ?? '')
  const [tags, setTags] = useState((editing?.tags ?? []).join('، '))
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const categoryTouched = useRef(!!(editing?.category || options.draft?.categoryId))

  const account = accounts.find((a) => a.id === accountId)
  const toAccount = accounts.find((a) => a.id === toAccountId)
  const commodity = commodities.get(account?.commodity ?? editing?.account.commodity ?? 'IRT')
  const toCommodity = commodities.get(toAccount?.commodity ?? 'IRT')
  const exchange = type === 'TRANSFER' && account && toAccount && account.commodity !== toAccount.commodity

  const liabilityEntry = !!editing && ['LOAN', 'DEBT', 'CREDIT'].includes(editing.account.type)
  // Opening debts are shown as the positive amount owed; adjustments keep their sign.
  const keepSign = !!balanceEntry && !(editing?.type === 'OPENING' && liabilityEntry)
  const displayOf = (amount: string | null | undefined, code: string, signed = false) => {
    if (!amount) return ''
    const value = !signed && amount.startsWith('-') ? amount.slice(1) : amount
    return toDisplayAmount(value, code, prefs)
  }
  const [amount, setAmount] = useState(() => displayOf(editing?.amount ?? options.draft?.amount, editing?.account.commodity ?? 'IRT', keepSign))
  const [toAmount, setToAmount] = useState(() => displayOf(editing?.toAmount, editing?.toAccount?.commodity ?? 'IRT'))
  const [fee, setFee] = useState(() => displayOf(editing?.fee, editing?.account.commodity ?? 'IRT'))
  const [showMore, setShowMore] = useState(!!(editing?.notes || editing?.tags.length || editing?.fee))

  // Suggest a category from learned merchants while the user types the description.
  useEffect(() => {
    if (type === 'TRANSFER' || categoryTouched.current || description.trim().length < 2) return
    const handle = setTimeout(() => {
      suggestCategory(description).then((id) => {
        if (id && !categoryTouched.current) setCategoryId(id)
      }).catch(() => undefined)
    }, 400)
    return () => clearTimeout(handle)
  }, [description, type])

  const rate = useMemo(() => {
    if (!exchange || !amount || !toAmount) return null
    try {
      const from = new Big(fromDisplayAmount(amount, account!.commodity, prefs))
      const to = new Big(fromDisplayAmount(toAmount, toAccount!.commodity, prefs))
      if (to.eq(0) || from.eq(0)) return null
      if (account!.commodity === 'IRT') return { text: `هر ${toCommodity.unitFa} ≈ ${f.money(from.div(to).round(0).toFixed(), commodity)}` }
      if (toAccount!.commodity === 'IRT') return { text: `هر ${commodity.unitFa} ≈ ${f.money(to.div(from).round(0).toFixed(), toCommodity)}` }
      return null
    } catch {
      return null
    }
  }, [exchange, amount, toAmount, account, toAccount, prefs, f, commodity, toCommodity])

  const submit = (addAnother: boolean) => {
    setError(null)
    if (!accountId) return setError('حساب را انتخاب کنید.')
    if (!amount || Number(amount) === 0) return setError('مبلغ را وارد کنید.')
    if (type === 'TRANSFER' && !balanceEntry) {
      if (!toAccountId) return setError('حساب مقصد را انتخاب کنید.')
      if (exchange && !toAmount) return setError('مقدار دریافتی در حساب مقصد را وارد کنید.')
    }
    const sourceCode = account?.commodity ?? 'IRT'
    const input: TransactionInput & { id?: number } = {
      id: editing?.id,
      type: balanceEntry ? editing!.type : type,
      date,
      accountId,
      amount: fromDisplayAmount(amount, sourceCode, prefs),
      toAccountId: type === 'TRANSFER' ? toAccountId : null,
      toAmount: type === 'TRANSFER' && exchange ? fromDisplayAmount(toAmount, toAccount!.commodity, prefs) : null,
      fee: type === 'TRANSFER' && fee ? fromDisplayAmount(fee, sourceCode, prefs) : null,
      categoryId: type === 'TRANSFER' ? null : categoryId,
      description: description || null,
      notes: notes || null,
      tags: tags.split(/[،,]/).map((t) => t.trim()).filter(Boolean),
    }
    save.mutate(input, {
      onSuccess: () => {
        try {
          localStorage.setItem(LAST_ACCOUNT_KEY, String(accountId))
        } catch {
          // ignore storage errors
        }
        toast.success(editing ? 'تراکنش ویرایش شد.' : 'تراکنش ثبت شد.')
        if (addAnother) {
          setAmount('')
          setDescription('')
          setNotes('')
          setTags('')
          setFee('')
          setToAmount('')
          categoryTouched.current = false
          setCategoryId(null)
        } else {
          onDone()
        }
      },
      onError: (e) => setError(e instanceof ApiError ? e.message : 'ثبت تراکنش ناموفق بود.'),
    })
  }

  const title = editing ? (balanceEntry ? TRANSACTION_TYPE_LABELS[editing.type] : 'ویرایش تراکنش') : 'تراکنش جدید'

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription className="sr-only">ثبت یا ویرایش درآمد، هزینه یا انتقال</DialogDescription>
      </DialogHeader>
      <DialogBody>
        <form id="transaction-form" className="grid gap-4" onSubmit={(e) => { e.preventDefault(); submit(false) }} noValidate>
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          {balanceEntry ? null : (
            <Segmented<EditableType>
              ariaLabel="نوع تراکنش"
              value={type}
              onChange={(t) => { setType(t); if (!categoryTouched.current) setCategoryId(null) }}
              options={[
                { value: 'EXPENSE', label: 'هزینه', className: 'text-expense' },
                { value: 'INCOME', label: 'درآمد', className: 'text-income' },
                { value: 'TRANSFER', label: 'انتقال / تبدیل', className: 'text-transfer' },
              ]}
            />
          )}
          <FormField label={type === 'TRANSFER' && exchange ? 'مقدار پرداختی' : 'مبلغ'}>
            <MoneyInput value={amount} onChange={setAmount} commodity={commodity} autoFocus showQuickButtons allowNegative={keepSign} />
          </FormField>
          {balanceEntry ? null : (
            <FormField label={type === 'INCOME' ? 'به حساب' : 'از حساب'}>
              <AccountSelect value={accountId} onChange={setAccountId} />
            </FormField>
          )}
          {type === 'TRANSFER' && !balanceEntry ? (
            <>
              <FormField label="به حساب">
                <AccountSelect value={toAccountId} onChange={setToAccountId} exclude={accountId} />
              </FormField>
              {exchange ? (
                <FormField label="مقدار دریافتی" hint={rate?.text}>
                  <MoneyInput value={toAmount} onChange={setToAmount} commodity={toCommodity} />
                </FormField>
              ) : null}
            </>
          ) : null}
          {type !== 'TRANSFER' && !balanceEntry ? (
            <FormField label="دسته‌بندی">
              <CategorySelect kind={type === 'INCOME' ? 'INCOME' : 'EXPENSE'} value={categoryId}
                onChange={(id) => { categoryTouched.current = true; setCategoryId(id) }} />
            </FormField>
          ) : null}
          <FormField label="تاریخ">
            <JalaliDatePicker value={date} onChange={setDate} />
          </FormField>
          {balanceEntry ? null : (
            <FormField label="شرح" optional>
              <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300}
                placeholder={type === 'EXPENSE' ? 'مثلاً خرید از سوپرمارکت' : type === 'INCOME' ? 'مثلاً حقوق مهر' : 'مثلاً خرید سکه'} />
            </FormField>
          )}
          {showMore || balanceEntry ? (
            <>
              {type === 'TRANSFER' && !balanceEntry ? (
                <FormField label="کارمزد" optional hint="کارمزد کارت‌به‌کارت یا انتقال؛ به‌عنوان هزینه حساب می‌شود.">
                  <MoneyInput value={fee} onChange={setFee} commodity={commodity} showWords={false} />
                </FormField>
              ) : null}
              {balanceEntry ? null : (
                <FormField label="برچسب‌ها" optional hint="با ویرگول جدا کنید؛ مثلاً سفر شمال، کاری">
                  <Input value={tags} onChange={(e) => setTags(e.target.value)} />
                </FormField>
              )}
              <FormField label="یادداشت" optional>
                <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={2000} />
              </FormField>
            </>
          ) : (
            <button type="button" className="w-fit cursor-pointer text-sm font-medium text-primary hover:underline" onClick={() => setShowMore(true)}>
              {type === 'TRANSFER' ? 'کارمزد، برچسب و یادداشت' : 'برچسب و یادداشت'}
            </button>
          )}
        </form>
      </DialogBody>
      <DialogFooter>
        {editing ? (
          <Button type="button" variant="ghost" className="text-destructive sm:me-auto" onClick={() => setConfirmDelete(true)}>حذف</Button>
        ) : (
          <Button type="button" variant="outline" loading={save.isPending} onClick={() => submit(true)}>ثبت و بعدی</Button>
        )}
        <Button type="submit" form="transaction-form" loading={save.isPending}>{editing ? 'ذخیره' : 'ثبت'}</Button>
      </DialogFooter>
      <ConfirmDialog open={confirmDelete} onOpenChange={setConfirmDelete} destructive title="حذف این تراکنش؟"
        description="مانده‌ی حساب‌ها بر این اساس دوباره محاسبه می‌شود." confirmLabel="حذف" loading={remove.isPending}
        onConfirm={() => editing && remove.mutate(editing.id, { onSuccess: () => { toast.success('تراکنش حذف شد.'); setConfirmDelete(false); onDone() } })} />
    </DialogContent>
  )
}
