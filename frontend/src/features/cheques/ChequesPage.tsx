import { AlertTriangle, Ban, CheckCircle2, Clock, FileText, MoreVertical, Pencil, Plus, RotateCcw, Trash2, Undo2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { PageHeader } from '@/components/ui/page-header'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Amount } from '@/components/finance/Amount'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { ROW, ROW_ACTIONS, ROW_AMOUNT, ROW_DETAILS, ROW_ICON } from '@/components/finance/row-layout'
import { AccountSelect } from '@/components/finance/selects'
import { useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { isTomanAsset } from '@/lib/accounts'
import { ApiError } from '@/lib/api/client'
import type { Cheque, ChequeDirection, ChequeStatus } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatDayOffset } from '@/lib/format/duration'
import { sumAmounts } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { BANK_NAMES, CHEQUE_STATUS_LABELS } from '@/lib/labels'
import { useChequeStatus, useCheques, useDeleteCheque } from './api'
import { ChequeFormDialog } from './ChequeFormDialog'

type StatusFilter = 'PENDING' | 'ALL'

function StatusBadge({ cheque }: { cheque: Cheque }) {
  switch (cheque.status) {
    case 'PENDING':
      return cheque.overdue
        ? <Badge variant="expense"><AlertTriangle className="size-3.5" />سررسید گذشته</Badge>
        : <Badge variant="outline"><Clock className="size-3.5" />{CHEQUE_STATUS_LABELS.PENDING}</Badge>
    case 'CLEARED':
      return <Badge variant="income"><CheckCircle2 className="size-3.5" />{CHEQUE_STATUS_LABELS.CLEARED}</Badge>
    case 'BOUNCED':
      return <Badge variant="expense"><RotateCcw className="size-3.5" />{CHEQUE_STATUS_LABELS.BOUNCED}</Badge>
    case 'CANCELLED':
      return <Badge variant="secondary"><Ban className="size-3.5" />{CHEQUE_STATUS_LABELS.CANCELLED}</Badge>
  }
}

/** «۱۲۳۴ ۵۶۷۸ ۹۰۱۲ ۳۴۵۶», read left to right like on the cheque. */
function SayadId({ value }: { value: string }) {
  const f = useFormat()
  return <bdi dir="ltr" className="whitespace-nowrap tabular">{f.digits(value.replace(/(\d{4})(?=\d)/g, '$1 '))}</bdi>
}

function ClearDialog({ cheque, onClose }: { cheque: Cheque; onClose: () => void }) {
  const f = useFormat()
  const status = useChequeStatus()
  const today = todayIso()
  const issued = cheque.direction === 'ISSUED'
  const [accountId, setAccountId] = useState<number | null>(cheque.accountId)
  const [date, setDate] = useState(today)
  const [error, setError] = useState<string | null>(null)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (accountId === null) return setError(issued ? 'حسابی را که چک از آن پاس شد انتخاب کنید.' : 'حسابی را که چک به آن واریز شد انتخاب کنید.')
    status.mutate({ id: cheque.id, status: 'CLEARED', accountId, date }, {
      onSuccess: () => { toast.success(issued ? 'چک پاس شد و تراکنشش ثبت شد.' : 'چک وصول شد و تراکنشش ثبت شد.'); onClose() },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ثبت انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{issued ? 'پاس شدن چک' : 'وصول چک'}</DialogTitle>
          <DialogDescription>
            <Amount value={cheque.amount} /> {issued ? 'از حساب انتخابی کم' : 'به حساب انتخابی اضافه'} و تراکنش آن ثبت می‌شود.
          </DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <FormField label={issued ? 'از حساب' : 'واریز به حساب'}>
            <AccountSelect value={accountId} onChange={setAccountId} filter={isTomanAsset} />
          </FormField>
          <FormField label="تاریخ">
            <JalaliDatePicker value={date} onChange={setDate} />
          </FormField>
          {cheque.dueDate <= today && date !== cheque.dueDate ? (
            <button type="button" onClick={() => setDate(cheque.dueDate)} className="-mt-2 w-fit cursor-pointer text-xs font-medium text-primary hover:underline">
              در تاریخ سررسید ({f.date(cheque.dueDate)})
            </button>
          ) : null}
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={status.isPending}>ثبت</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function ChequeRow({ cheque, accountName, onClear, onEdit, onDelete }: {
  cheque: Cheque; accountName?: string; onClear: () => void; onEdit: () => void; onDelete: () => void
}) {
  const f = useFormat()
  const status = useChequeStatus()
  const issued = cheque.direction === 'ISSUED'
  const pending = cheque.status === 'PENDING'
  const change = (target: ChequeStatus, message: string) =>
    status.mutate({ id: cheque.id, status: target }, {
      onSuccess: () => toast.success(message),
      onError: (err) => toast.error(err instanceof ApiError ? err.message : 'تغییر انجام نشد.'),
    })
  const title = cheque.counterparty ? `${issued ? 'به' : 'از'} ${cheque.counterparty}` : issued ? 'چک صادره' : 'چک دریافتی'
  const details = [
    cheque.bank ? `بانک ${BANK_NAMES[cheque.bank]}` : null,
    accountName ? (issued ? `از ${accountName}` : `به ${accountName}`) : null,
    cheque.serial ? `سریال ${f.digits(cheque.serial)}` : null,
  ].filter(Boolean).join(' | ')

  return (
    <li className={cn(ROW, (cheque.status === 'CANCELLED' || cheque.status === 'CLEARED') && 'opacity-75')}>
      <span className={cn(ROW_ICON, 'flex size-10 shrink-0 items-center justify-center rounded-full',
        issued ? 'bg-expense/10 text-expense' : 'bg-income/10 text-income')}>
        <FileText className="size-4" />
      </span>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-medium">
        <span>{title}</span>
        <StatusBadge cheque={cheque} />
      </p>
      <Amount value={cheque.amount} tone={issued ? 'expense' : 'income'} sign={issued ? '-' : '+'} className={cn(ROW_AMOUNT, 'text-sm font-semibold')} />
      <div className={cn(ROW_DETAILS, 'grid gap-0.5 text-xs text-muted-foreground')}>
        <p>
          سررسید {f.date(cheque.dueDate)}
          {pending ? ` (${formatDayOffset(cheque.daysToDue, f.prefs.digits)})` : cheque.settledOn ? ` | ${CHEQUE_STATUS_LABELS[cheque.status]} در ${f.date(cheque.settledOn)}` : ''}
        </p>
        {details || cheque.sayadId ? (
          <p>{details}{details && cheque.sayadId ? ' | ' : ''}{cheque.sayadId ? <>صیادی <SayadId value={cheque.sayadId} /></> : null}</p>
        ) : null}
      </div>
      <div className={ROW_ACTIONS}>
        {pending ? <Button size="sm" variant={cheque.overdue ? 'default' : 'outline'} onClick={onClear}>{issued ? 'پاس شد' : 'وصول شد'}</Button> : null}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های چک ${title}`}><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            {cheque.status === 'BOUNCED' ? <DropdownMenuItem onSelect={onClear}><CheckCircle2 />{issued ? 'بعداً پاس شد' : 'بعداً وصول شد'}</DropdownMenuItem> : null}
            {pending ? (
              <>
                <DropdownMenuItem onSelect={() => change('BOUNCED', 'چک برگشتی ثبت شد.')}><RotateCcw />برگشت خورد</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => change('CANCELLED', 'چک باطل شد.')}><Ban />باطل شد</DropdownMenuItem>
              </>
            ) : (
              <DropdownMenuItem onSelect={() => change('PENDING', cheque.status === 'CLEARED' ? 'چک به حالت در انتظار برگشت و تراکنشش حذف شد.' : 'چک دوباره در انتظار است.')}>
                <Undo2 />بازگرداندن به «در انتظار»
              </DropdownMenuItem>
            )}
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
            <DropdownMenuItem onSelect={onDelete} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </li>
  )
}

function Totals({ cheques }: { cheques: Cheque[] }) {
  const f = useFormat()
  const pending = cheques.filter((c) => c.status === 'PENDING')
  const tile = (direction: ChequeDirection) => {
    const items = pending.filter((c) => c.direction === direction)
    const next = items[0]
    return (
      <Card>
        <CardContent className="p-4">
          <p className="text-xs text-muted-foreground">{direction === 'ISSUED' ? 'چک‌های صادره‌ی پاس‌نشده' : 'چک‌های دریافتی وصول‌نشده'}</p>
          <Amount value={sumAmounts(items.map((c) => c.amount))} className="text-xl font-bold" />
          <p className="text-xs text-muted-foreground">
            {items.length === 0 ? 'چکی در انتظار نیست' : `${f.number(items.length)} چک | نزدیک‌ترین: ${f.date(next.dueDate)}`}
          </p>
        </CardContent>
      </Card>
    )
  }
  const overdue = pending.filter((c) => c.overdue)
  return (
    <div className="mb-5 grid grid-cols-1 gap-3">
      {overdue.length > 0 ? (
        <Alert variant="warning">
          <AlertTriangle />
          <span>سررسید {f.number(overdue.length)} چک گذشته و وضعیتش ثبت نشده است؛ اگر پاس یا وصول شده، ثبتش کنید.</span>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {tile('ISSUED')}
        {tile('RECEIVED')}
      </div>
    </div>
  )
}

export function ChequesPage() {
  const f = useFormat()
  const [params, setParams] = useSearchParams()
  const direction: ChequeDirection = params.get('tab') === 'received' ? 'RECEIVED' : 'ISSUED'
  const [filter, setFilter] = useState<StatusFilter>('PENDING')
  const { data: cheques, isPending } = useCheques()
  const { data: accounts = [] } = useAccounts(true)
  const accountNames = useMemo(() => new Map(accounts.map((a) => [a.id, a.name])), [accounts])
  const remove = useDeleteCheque()
  const [editing, setEditing] = useState<{ cheque?: Cheque } | null>(null)
  const [clearing, setClearing] = useState<Cheque | null>(null)
  const [deleting, setDeleting] = useState<Cheque | null>(null)

  const ofDirection = (cheques ?? []).filter((c) => c.direction === direction)
  const rows = filter === 'PENDING' ? ofDirection.filter((c) => c.status === 'PENDING') : ofDirection
  const count = (d: ChequeDirection) => (cheques ?? []).filter((c) => c.direction === d && c.status === 'PENDING').length

  return (
    <>
      <PageHeader
        title="چک‌ها"
        description="چک‌های صادره و دریافتی، سررسیدها و وضعیت پاس شدن"
        actions={<Button onClick={() => setEditing({})}><Plus />چک جدید</Button>}
      />
      {isPending ? (
        <div className="grid gap-4"><Skeleton className="h-28" /><Skeleton className="h-64" /></div>
      ) : !cheques?.length ? (
        <EmptyState
          icon={FileText}
          title="هنوز چکی ثبت نکرده‌اید"
          description="چک‌هایی را که کشیده‌اید یا گرفته‌اید با مبلغ و سررسید ثبت کنید؛ کارنامه نزدیک سررسید یادآوری می‌کند، آن‌ها را در پیش‌بینی نقدینگی حساب می‌کند و با پاس شدن، تراکنش را ثبت می‌کند."
          action={<Button onClick={() => setEditing({})}><Plus />ثبت اولین چک</Button>}
          className="bg-card"
        />
      ) : (
        <>
          <Totals cheques={cheques} />
          <Card>
            <CardContent className="grid grid-cols-1 gap-3 p-4 sm:p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Tabs value={direction} onValueChange={(v) => setParams(v === 'RECEIVED' ? { tab: 'received' } : {}, { replace: true })}>
                  <TabsList>
                    <TabsTrigger value="ISSUED">صادره{count('ISSUED') ? ` (${f.number(count('ISSUED'))})` : ''}</TabsTrigger>
                    <TabsTrigger value="RECEIVED">دریافتی{count('RECEIVED') ? ` (${f.number(count('RECEIVED'))})` : ''}</TabsTrigger>
                  </TabsList>
                </Tabs>
                <Segmented<StatusFilter> ariaLabel="وضعیت" size="sm" value={filter} onChange={setFilter}
                  options={[{ value: 'PENDING', label: 'در انتظار' }, { value: 'ALL', label: 'همه' }]} />
              </div>
              {rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  {filter === 'PENDING' ? `چک ${direction === 'ISSUED' ? 'صادره‌ی' : 'دریافتی'} در انتظاری ندارید.` : 'چکی در این بخش نیست.'}
                </p>
              ) : (
                <ul className="divide-y">
                  {rows.map((c) => (
                    <ChequeRow key={c.id} cheque={c} accountName={c.accountId ? accountNames.get(c.accountId) : undefined}
                      onClear={() => setClearing(c)} onEdit={() => setEditing({ cheque: c })} onDelete={() => setDeleting(c)} />
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </>
      )}
      <ChequeFormDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} cheque={editing?.cheque} direction={direction} />
      <Dialog open={clearing !== null} onOpenChange={(o) => !o && setClearing(null)}>
        {clearing ? <ClearDialog key={clearing.id} cheque={clearing} onClose={() => setClearing(null)} /> : null}
      </Dialog>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        destructive
        title="حذف این چک؟"
        description={deleting?.status === 'CLEARED' ? 'تراکنشی که با پاس شدن این چک ثبت شده هم حذف می‌شود.' : 'چک از فهرست و یادآوری‌ها حذف می‌شود.'}
        confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('چک حذف شد.'); setDeleting(null) } })}
      />
    </>
  )
}
