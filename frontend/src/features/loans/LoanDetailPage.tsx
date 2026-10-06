import { AlertTriangle, ArrowRight, Landmark, MoreVertical, Pencil, Trash2, Undo2, WalletCards } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { Progress } from '@/components/ui/progress'
import { Segmented } from '@/components/ui/segmented'
import { PageSpinner } from '@/components/ui/spinner'
import { Amount } from '@/components/finance/Amount'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { ROW, ROW_ACTIONS, ROW_AMOUNT, ROW_DETAILS, ROW_ICON } from '@/components/finance/row-layout'
import { AccountSelect } from '@/components/finance/selects'
import { ErrorScreen } from '@/app/ErrorScreen'
import { usePrefs, useFormat } from '@/app/preferences'
import { isTomanAsset } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { Installment, Loan } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatDayOffset, formatMonths } from '@/lib/format/duration'
import { compareAmounts, fromDisplayAmount, IRT, sumAmounts } from '@/lib/format/money'
import { daysBetween, todayIso } from '@/lib/jalali'
import { BANK_NAMES, LOAN_METHOD_LABELS } from '@/lib/labels'
import { useDeleteLoan, useLoan, usePayInstallment, useUndoPayment } from './api'
import { InstallmentBadge } from './InstallmentBadge'
import { LoanFormDialog } from './LoanFormDialog'

/** Rows shown before «نمایش همه»; long mortgages run to hundreds of installments. */
const PAGE = 12

function PayDialog({ loan, installment, onClose }: { loan: Loan; installment: Installment; onClose: () => void }) {
  const f = useFormat()
  const prefs = usePrefs()
  const pay = usePayInstallment()
  const today = todayIso()
  const [accountId, setAccountId] = useState<number | null>(loan.paymentAccountId)
  const [date, setDate] = useState(today)
  const [penalty, setPenalty] = useState('')
  const [error, setError] = useState<string | null>(null)
  const penaltyToman = penalty ? fromDisplayAmount(penalty, 'IRT', prefs) : null
  const late = installment.dueDate < date

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (accountId === null) return setError('حسابی را که قسط از آن پرداخت شده انتخاب کنید.')
    pay.mutate({ id: loan.id, number: installment.number, accountId, date, penalty: penaltyToman }, {
      onSuccess: () => {
        toast.success(`قسط ${f.number(installment.number)} ثبت شد.`)
        onClose()
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ثبت انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>پرداخت قسط {f.number(installment.number)} {loan.name}</DialogTitle>
          <DialogDescription>اصل قسط از حساب پرداخت به حساب وام منتقل می‌شود و سود آن (با جریمه، اگر باشد) هزینه‌ی «سود و کارمزد وام» ثبت می‌شود.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <dl className="grid grid-cols-3 gap-2 rounded-xl bg-muted/60 p-3 text-sm">
            <div><dt className="text-xs text-muted-foreground">مبلغ قسط</dt><dd><Amount value={installment.amount} className="font-semibold" /></dd></div>
            <div><dt className="text-xs text-muted-foreground">اصل</dt><dd><Amount value={installment.principal} /></dd></div>
            <div><dt className="text-xs text-muted-foreground">سود</dt><dd><Amount value={installment.interest} /></dd></div>
          </dl>
          <FormField label="پرداخت از حساب">
            <AccountSelect value={accountId} onChange={setAccountId} filter={isTomanAsset} />
          </FormField>
          <FormField label="تاریخ پرداخت">
            <JalaliDatePicker value={date} onChange={setDate} />
          </FormField>
          {installment.dueDate <= today && date !== installment.dueDate ? (
            <button type="button" onClick={() => setDate(installment.dueDate)} className="-mt-2 w-fit cursor-pointer text-xs font-medium text-primary hover:underline">
              سر موعد پرداخت کردم ({f.date(installment.dueDate)})
            </button>
          ) : null}
          <FormField label="جریمه‌ی دیرکرد" optional hint={late ? `این قسط ${f.number(daysBetween(installment.dueDate, date))} روز دیرتر از سررسید پرداخت شده است.` : undefined}>
            <MoneyInput value={penalty} onChange={setPenalty} commodity={IRT} showWords={false} />
          </FormField>
          <p className="flex items-center justify-between rounded-xl border px-3 py-2 text-sm">
            جمع پرداخت
            <Amount value={sumAmounts([installment.amount, penaltyToman ?? '0'])} className="font-semibold" />
          </p>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={pay.isPending}>ثبت پرداخت</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function DeleteLoanDialog({ loan, open, onOpenChange }: { loan: Loan; open: boolean; onOpenChange: (open: boolean) => void }) {
  const navigate = useNavigate()
  const remove = useDeleteLoan()
  const [withAccount, setWithAccount] = useState(false)
  const options = [
    { value: false, title: 'فقط جدول اقساط', description: 'حساب وام و تراکنش‌های ثبت‌شده (دریافت وام و پرداخت‌ها) می‌مانند؛ وام دیگر یادآوری نمی‌شود.' },
    { value: true, title: 'وام را کامل پاک کن', description: 'حساب وام، دریافت وام، پرداخت اقساط و هزینه‌های سود همه حذف می‌شوند؛ انگار این وام هرگز ثبت نشده است.' },
  ]
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>حذف وام «{loan.name}»</DialogTitle>
        </DialogHeader>
        <DialogBody>
          <div role="radiogroup" aria-label="نوع حذف" className="grid gap-2">
            {options.map((o) => (
              <button key={String(o.value)} type="button" role="radio" aria-checked={withAccount === o.value} onClick={() => setWithAccount(o.value)}
                className={cn('grid cursor-pointer gap-1 rounded-xl border p-3 text-start transition-colors',
                  withAccount === o.value ? 'border-primary bg-primary/5' : 'hover:bg-accent/60')}>
                <span className="text-sm font-medium">{o.title}</span>
                <span className="text-xs text-muted-foreground">{o.description}</span>
              </button>
            ))}
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>انصراف</Button>
          <Button variant="destructive" loading={remove.isPending} onClick={() => remove.mutate({ id: loan.id, withAccount }, {
            onSuccess: () => { toast.success('وام حذف شد.'); navigate('/loans', { replace: true }) },
          })}>حذف</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function InstallmentRow({ item, onPay, onUndo }: { item: Installment; onPay: () => void; onUndo: () => void }) {
  const f = useFormat()
  const unpaid = item.status !== 'PAID' && item.status !== 'PAID_BEFORE'
  const extra = item.paidAmount !== null && compareAmounts(item.paidAmount, item.amount) !== 0
  return (
    <li className={ROW}>
      <span className={cn(ROW_ICON, 'flex size-9 items-center justify-center justify-self-center rounded-full text-xs font-medium tabular',
        unpaid ? 'bg-muted text-foreground' : 'bg-income/12 text-income')}>{f.number(item.number)}</span>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="font-medium">{f.date(item.dueDate)}</span>
        <InstallmentBadge status={item.status} />
      </p>
      <Amount value={item.amount} className={cn(ROW_AMOUNT, 'text-sm font-semibold')} />
      <p className={cn(ROW_DETAILS, 'text-xs text-muted-foreground')}>
        اصل <Amount value={item.principal} /> | سود <Amount value={item.interest} />
        {item.paidOn ? <> | پرداخت {f.date(item.paidOn)}{extra ? <> (<Amount value={item.paidAmount} /> با جریمه)</> : null}</> : null}
        <span className="hidden sm:inline"> | مانده پس از آن <Amount value={item.balanceAfter} /></span>
      </p>
      <div className={ROW_ACTIONS}>
        {unpaid ? (
          <Button size="sm" variant={item.status === 'OVERDUE' ? 'default' : 'outline'} onClick={onPay}>پرداخت</Button>
        ) : item.status === 'PAID' ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های قسط ${f.number(item.number)}`}><MoreVertical /></Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem onSelect={onUndo}><Undo2 />برگرداندن پرداخت</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </div>
    </li>
  )
}

type Filter = 'unpaid' | 'all'

function Schedule({ loan, onPay, onUndo }: { loan: Loan; onPay: (i: Installment) => void; onUndo: (i: Installment) => void }) {
  const f = useFormat()
  const all = loan.installments ?? []
  const unpaid = all.filter((i) => i.status !== 'PAID' && i.status !== 'PAID_BEFORE')
  const [filter, setFilter] = useState<Filter>(unpaid.length > 0 ? 'unpaid' : 'all')
  const [expanded, setExpanded] = useState(false)
  const rows = filter === 'unpaid' ? unpaid : all
  const shown = expanded ? rows : rows.slice(0, PAGE)
  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>جدول اقساط</CardTitle>
        <Segmented<Filter> ariaLabel="نمایش اقساط" size="sm" value={filter} onChange={(v) => { setFilter(v); setExpanded(false) }}
          options={[{ value: 'unpaid', label: `پرداخت‌نشده (${f.number(unpaid.length)})` }, { value: 'all', label: `همه (${f.number(all.length)})` }]} />
      </CardHeader>
      <CardContent className="pt-0">
        {rows.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">همه‌ی اقساط این وام پرداخت شده است.</p>
        ) : (
          <ul className="divide-y">
            {shown.map((i) => <InstallmentRow key={i.number} item={i} onPay={() => onPay(i)} onUndo={() => onUndo(i)} />)}
          </ul>
        )}
        {rows.length > shown.length ? (
          <div className="flex justify-center pt-2">
            <Button variant="outline" onClick={() => setExpanded(true)}>نمایش {f.number(rows.length - shown.length)} قسط دیگر</Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

export function LoanDetailPage() {
  const id = Number(useParams().id)
  const f = useFormat()
  const { data: loan, isPending, isError, error } = useLoan(id)
  const undo = useUndoPayment()
  const [editOpen, setEditOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [paying, setPaying] = useState<Installment | null>(null)
  const [undoing, setUndoing] = useState<Installment | null>(null)

  if (isPending) return <PageSpinner />
  if (isError || !loan) {
    return error instanceof ApiError && error.status === 404
      ? <EmptyState title="وام پیدا نشد" action={<Button asChild variant="outline"><Link to="/loans">بازگشت</Link></Button>} />
      : <ErrorScreen />
  }

  const rate = Number(loan.annualRate)
  const monthsLeft = loan.termMonths - loan.paidCount

  return (
    <>
      <div className="mb-4">
        <Button asChild variant="ghost" size="sm"><Link to="/loans"><ArrowRight />وام‌ها</Link></Button>
      </div>
      <div className="mb-5 flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"><Landmark className="size-5" /></span>
          <div className="min-w-0">
            <h1 className="text-xl font-bold sm:text-2xl">{loan.name}</h1>
            <p className="text-sm text-muted-foreground">
              {loan.bank ? `بانک ${BANK_NAMES[loan.bank]} | ` : ''}<Amount value={loan.principal} /> | {rate === 0 ? 'بدون سود' : `سود ${f.percent(rate / 100, 2)}`}
              {' '}| {f.number(loan.termMonths)} قسط | {LOAN_METHOD_LABELS[loan.method]}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline"><Link to={`/accounts/${loan.accountId}`}><WalletCards />حساب وام</Link></Button>
          <Button variant="outline" size="icon" aria-label="ویرایش" onClick={() => setEditOpen(true)}><Pencil /></Button>
          <Button variant="outline" size="icon" aria-label="حذف" className="text-destructive" onClick={() => setDeleteOpen(true)}><Trash2 /></Button>
        </div>
      </div>

      {loan.overdueCount > 0 ? (
        <Alert variant="destructive" className="mb-5">
          <AlertTriangle />
          <span>{f.number(loan.overdueCount)} قسط به مبلغ <Amount value={loan.overdueAmount} className="font-semibold" /> از سررسیدش گذشته و پرداخت نشده است.</span>
        </Alert>
      ) : null}

      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">مانده‌ی وام</p>
            <Amount value={loan.outstanding} className="text-xl font-bold" />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="grid gap-1.5 p-4">
            <p className="text-xs text-muted-foreground">اقساط پرداخت‌شده</p>
            <p className="text-xl font-bold tabular">{f.number(loan.paidCount)} <span className="text-sm font-normal text-muted-foreground">از {f.number(loan.termMonths)}</span></p>
            <Progress value={loan.paidCount / loan.termMonths} tone={monthsLeft === 0 ? 'income' : 'primary'} label="پیشرفت بازپرداخت" />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">سود باقی‌مانده</p>
            <Amount value={loan.remainingInterest} className="text-xl font-bold" />
            <p className="text-xs text-muted-foreground">از کل سود <Amount value={loan.totalInterest} /></p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">{monthsLeft === 0 ? 'تسویه' : 'قسط بعدی'}</p>
            {loan.next ? (
              <>
                <Amount value={loan.next.amount} className="text-xl font-bold" />
                <p className="text-xs text-muted-foreground">
                  {f.date(loan.next.dueDate)} | {formatDayOffset(daysBetween(todayIso(), loan.next.dueDate), f.prefs.digits)}
                </p>
              </>
            ) : <p className="text-xl font-bold text-income">همه پرداخت شد</p>}
            {loan.endDate && monthsLeft > 0 ? <p className="text-xs text-muted-foreground">پایان: {f.date(loan.endDate)} ({formatMonths(monthsLeft, f.prefs.digits)})</p> : null}
          </CardContent>
        </Card>
      </div>

      <Schedule loan={loan} onPay={setPaying} onUndo={setUndoing} />

      {loan.notes ? <p className="mt-4 whitespace-pre-line text-sm text-muted-foreground">{loan.notes}</p> : null}

      <LoanFormDialog open={editOpen} onOpenChange={setEditOpen} loan={loan} />
      <DeleteLoanDialog loan={loan} open={deleteOpen} onOpenChange={setDeleteOpen} />
      <Dialog open={paying !== null} onOpenChange={(o) => !o && setPaying(null)}>
        {paying ? <PayDialog key={paying.number} loan={loan} installment={paying} onClose={() => setPaying(null)} /> : null}
      </Dialog>
      <ConfirmDialog
        open={undoing !== null}
        onOpenChange={(o) => !o && setUndoing(null)}
        destructive
        title={`برگرداندن پرداخت قسط ${f.number(undoing?.number ?? 0)}؟`}
        description="تراکنش‌های ثبت‌شده برای این قسط (انتقال اصل و هزینه‌ی سود) حذف می‌شوند و قسط دوباره پرداخت‌نشده حساب می‌شود."
        confirmLabel="برگرداندن"
        loading={undo.isPending}
        onConfirm={() => undoing && undo.mutate({ id: loan.id, number: undoing.number }, {
          onSuccess: () => { toast.success('پرداخت برگردانده شد.'); setUndoing(null) },
        })}
      />
    </>
  )
}
