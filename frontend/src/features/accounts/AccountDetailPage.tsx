import { Archive, ArchiveRestore, ArrowRight, Pencil, Plus, Scale, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { PageSpinner } from '@/components/ui/spinner'
import { Amount } from '@/components/finance/Amount'
import { AccountIcon } from '@/components/finance/icons'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { usePrefs } from '@/app/preferences'
import { useCommodityMap } from '@/features/commodities/api'
import { useTransactionPages } from '@/features/transactions/api'
import { TransactionList } from '@/features/transactions/TransactionList'
import { useTransactionDialog } from '@/features/transactions/TransactionDialog'
import { ApiError } from '@/lib/api/client'
import { fromDisplayAmount } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { ACCOUNT_TYPE_LABELS, BANK_NAMES } from '@/lib/labels'
import { useAccount, useArchiveAccount, useCostBasis, useDeleteAccount, useReconcileAccount } from './api'
import { AccountFormDialog } from './AccountFormDialog'
import { ErrorScreen } from '@/app/ErrorScreen'

function ReconcileDialog({ open, onOpenChange, accountId, commodity, liability }: {
  open: boolean; onOpenChange: (o: boolean) => void; accountId: number; commodity: string; liability: boolean
}) {
  const prefs = usePrefs()
  const commodities = useCommodityMap()
  const reconcile = useReconcileAccount()
  const [value, setValue] = useState('')
  const [date, setDate] = useState(todayIso())
  const [error, setError] = useState<string | null>(null)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>تطبیق موجودی</DialogTitle>
          <DialogDescription>موجودی واقعی (مثلاً از اپ بانک) را وارد کنید؛ اختلاف به‌صورت یک تراکنش «تطبیق موجودی» ثبت می‌شود.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <FormField label={liability ? 'مانده‌ی واقعی بدهی' : 'موجودی واقعی'}>
            <MoneyInput value={value} onChange={setValue} commodity={commodities.get(commodity)} allowNegative={!liability} autoFocus />
          </FormField>
          <FormField label="تاریخ">
            <JalaliDatePicker value={date} onChange={setDate} />
          </FormField>
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>انصراف</Button>
          <Button loading={reconcile.isPending} disabled={!value} onClick={() => reconcile.mutate(
            { id: accountId, actualBalance: fromDisplayAmount(value, commodity, prefs), date },
            { onSuccess: () => { toast.success('موجودی تطبیق داده شد.'); onOpenChange(false); setValue('') }, onError: (e) => setError(e.message) },
          )}>ثبت</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function AccountDetailPage() {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const openTransaction = useTransactionDialog()
  const commodities = useCommodityMap()
  const { data: account, isPending, isError, error } = useAccount(id)
  const costBasis = useCostBasis(account)
  const transactions = useTransactionPages({ accountId: id })
  const archive = useArchiveAccount()
  const remove = useDeleteAccount()
  const [editOpen, setEditOpen] = useState(false)
  const [reconcileOpen, setReconcileOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  if (isPending) return <PageSpinner />
  if (isError || !account) {
    return error instanceof ApiError && error.status === 404
      ? <EmptyState title="حساب پیدا نشد" action={<Button asChild variant="outline"><Link to="/accounts">بازگشت</Link></Button>} />
      : <ErrorScreen />
  }

  const commodity = commodities.get(account.commodity)
  const items = transactions.data?.pages.flatMap((p) => p.items) ?? []
  const cb = costBasis.data

  return (
    <>
      <div className="mb-4">
        <Button asChild variant="ghost" size="sm"><Link to="/accounts"><ArrowRight />حساب‌ها</Link></Button>
      </div>
      <div className="mb-5 flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary"><AccountIcon type={account.type} className="size-5" /></span>
          <div>
            <h1 className="text-xl font-bold sm:text-2xl">{account.name}</h1>
            <p className="text-sm text-muted-foreground">
              {ACCOUNT_TYPE_LABELS[account.type]}{account.bank ? ` | بانک ${BANK_NAMES[account.bank]}` : ''}{account.counterparty ? ` | ${account.counterparty}` : ''}
              {account.commodity !== 'IRT' ? ` | ${commodity.nameFa}` : ''}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => openTransaction({ draft: { accountId: account.id } })}><Plus />تراکنش</Button>
          <Button variant="outline" onClick={() => setReconcileOpen(true)}><Scale />تطبیق موجودی</Button>
          <Button variant="outline" size="icon" aria-label="ویرایش" onClick={() => setEditOpen(true)}><Pencil /></Button>
          <Button variant="outline" size="icon" aria-label={account.archived ? 'خروج از بایگانی' : 'بایگانی'}
            onClick={() => archive.mutate({ id: account.id, archived: !account.archived }, { onSuccess: () => toast.success(account.archived ? 'حساب از بایگانی خارج شد.' : 'حساب بایگانی شد.') })}>
            {account.archived ? <ArchiveRestore /> : <Archive />}
          </Button>
          <Button variant="outline" size="icon" aria-label="حذف" className="text-destructive" onClick={() => { setDeleteError(null); setDeleteOpen(true) }}><Trash2 /></Button>
        </div>
      </div>

      <div className="mb-5 grid gap-3 sm:grid-cols-3">
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">{account.liability ? 'مانده‌ی بدهی' : 'موجودی'}</p>
            <Amount value={account.liability ? account.balance.replace(/^-/, '') : account.balance} commodity={account.commodity}
              tone={account.liability ? 'expense' : 'neutral'} className="text-xl font-bold" />
          </CardContent>
        </Card>
        {account.commodity !== 'IRT' ? (
          <Card>
            <CardContent className="p-4">
              <p className="text-xs text-muted-foreground">ارزش روز</p>
              {account.priced ? <Amount value={account.valueToman} className="text-xl font-bold" /> : <p className="text-sm text-warning">قیمت ثبت نشده</p>}
              {account.priceStale ? <p className="text-xs text-warning">قیمت قدیمی است</p> : null}
            </CardContent>
          </Card>
        ) : null}
        {cb && Number(cb.quantity) > 0 ? (
          <Card>
            <CardContent className="p-4">
              <p className="text-xs text-muted-foreground">سود/زیان (نسبت به میانگین خرید)</p>
              <Amount value={cb.unrealizedToman} tone="auto" className="text-xl font-bold" />
              <p className="text-xs text-muted-foreground">
                میانگین خرید هر {commodity.unitFa}: <Amount value={cb.averageCostToman} />
                {!cb.costComplete ? ' (بخشی از خریدها بدون قیمت)' : ''}
              </p>
            </CardContent>
          </Card>
        ) : null}
      </div>

      <Card>
        <CardHeader><CardTitle>تراکنش‌های این حساب</CardTitle></CardHeader>
        <CardContent className="p-2 sm:p-3">
          {items.length === 0 ? (
            <EmptyState title="تراکنشی ثبت نشده" className="border-0" />
          ) : (
            <TransactionList items={items} perspective={account.id} />
          )}
          {transactions.hasNextPage ? (
            <div className="flex justify-center p-2">
              <Button variant="outline" loading={transactions.isFetchingNextPage} onClick={() => transactions.fetchNextPage()}>نمایش بیشتر</Button>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <AccountFormDialog open={editOpen} onOpenChange={setEditOpen} account={account} />
      <ReconcileDialog open={reconcileOpen} onOpenChange={setReconcileOpen} accountId={account.id} commodity={account.commodity} liability={account.liability} />
      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        destructive
        title={`حذف حساب «${account.name}»؟`}
        description={deleteError ?? 'حساب و همه‌ی تراکنش‌هایش برای همیشه حذف می‌شوند. اگر فقط نمی‌خواهید دیده شود، بایگانی‌اش کنید.'}
        confirmLabel="حذف دائمی"
        loading={remove.isPending}
        onConfirm={() => remove.mutate({ id: account.id, force: true }, {
          onSuccess: () => { toast.success('حساب حذف شد.'); navigate('/accounts', { replace: true }) },
          onError: (e) => setDeleteError(e.message),
        })}
      />
    </>
  )
}
