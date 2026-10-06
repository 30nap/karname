import { ArrowLeftRight, BellRing, CalendarClock, MoreVertical, Pause, Pencil, Play, Plus, Repeat, SkipForward, SlidersHorizontal, Trash2, Zap } from 'lucide-react'
import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { PageHeader } from '@/components/ui/page-header'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { ROW, ROW_ACTIONS, ROW_AMOUNT, ROW_DETAILS, ROW_ICON } from '@/components/finance/row-layout'
import { usePrefs, useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCategories } from '@/features/categories/api'
import { useCommodityMap } from '@/features/commodities/api'
import { ApiError } from '@/lib/api/client'
import type { Account, Category, Occurrence, RecurringRule } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatDayOffset } from '@/lib/format/duration'
import { fromDisplayAmount, toDisplayAmount } from '@/lib/format/money'
import { daysBetween, todayIso } from '@/lib/jalali'
import { describeRecurrence } from '@/lib/recurrence'
import { toRuleInput, useDeleteRule, usePendingOccurrences, usePostOccurrence, useRecurringRules, useSaveRule, useSkipOccurrence } from './api'
import { RecurringRuleDialog } from './RecurringRuleDialog'

type Flow = Pick<RecurringRule, 'type' | 'amount' | 'accountId' | 'toAccountId' | 'categoryId'>

function useLookups() {
  const { data: accounts = [] } = useAccounts(true)
  const { data: categories = [] } = useCategories()
  return useMemo(() => {
    const byId = new Map<number, Category>(categories.map((c) => [c.id, c]))
    return {
      account: new Map<number, Account>(accounts.map((a) => [a.id, a])),
      // subcategories without an icon show their parent's, as transactions do
      icon: (id: number | null) => {
        const c = id === null ? undefined : byId.get(id)
        return c?.icon ?? (c?.parentId ? byId.get(c.parentId)?.icon : null) ?? null
      },
    }
  }, [accounts, categories])
}

function FlowIcon({ flow, icon }: { flow: Flow; icon: string | null }) {
  return (
    <span className={cn(ROW_ICON, 'flex size-10 shrink-0 items-center justify-center rounded-full',
      flow.type === 'EXPENSE' && 'bg-expense/10 text-expense',
      flow.type === 'INCOME' && 'bg-income/10 text-income',
      flow.type === 'TRANSFER' && 'bg-transfer/10 text-transfer')}>
      {flow.type === 'TRANSFER' ? <ArrowLeftRight className="size-4" /> : <CategoryIcon name={icon} />}
    </span>
  )
}

function FlowAmount({ flow, account }: { flow: Flow; account?: Account }) {
  const commodity = account?.commodity ?? 'IRT'
  const className = cn(ROW_AMOUNT, 'text-sm font-semibold')
  if (flow.type === 'EXPENSE') return <Amount value={flow.amount} commodity={commodity} tone="expense" sign="-" className={className} />
  if (flow.type === 'INCOME') return <Amount value={flow.amount} commodity={commodity} tone="income" sign="+" className={className} />
  return <Amount value={flow.amount} commodity={commodity} className={className} />
}

function accountsText(flow: Flow, lookups: ReturnType<typeof useLookups>) {
  const from = lookups.account.get(flow.accountId)?.name ?? ''
  if (flow.type === 'TRANSFER') return `${from} ← ${lookups.account.get(flow.toAccountId ?? -1)?.name ?? ''}`
  return from
}

function PostDialog({ occurrence, onClose }: { occurrence: Occurrence; onClose: () => void }) {
  const prefs = usePrefs()
  const f = useFormat()
  const commodities = useCommodityMap()
  const lookups = useLookups()
  const post = usePostOccurrence()
  const code = lookups.account.get(occurrence.accountId)?.commodity ?? 'IRT'
  const today = todayIso()
  const [amount, setAmount] = useState(toDisplayAmount(occurrence.amount, code, prefs))
  const [date, setDate] = useState(occurrence.date > today ? today : occurrence.date)
  const [error, setError] = useState<string | null>(null)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!amount || Number(amount) <= 0) return setError('مبلغ را وارد کنید.')
    post.mutate({ ruleId: occurrence.ruleId, date: occurrence.date, amount: fromDisplayAmount(amount, code, prefs), actualDate: date }, {
      onSuccess: () => { toast.success(`«${occurrence.name}» ثبت شد.`); onClose() },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ثبت انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>ثبت «{occurrence.name}»</DialogTitle>
          <DialogDescription>نوبت {f.date(occurrence.date)}؛ اگر مبلغ یا تاریخ واقعی فرق داشت، این‌جا اصلاحش کنید.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <FormField label="مبلغ">
            <MoneyInput value={amount} onChange={setAmount} commodity={commodities.get(code)} autoFocus />
          </FormField>
          <FormField label="تاریخ تراکنش">
            <JalaliDatePicker value={date} onChange={setDate} />
          </FormField>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={post.isPending}>ثبت</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function PendingRow({ occurrence, onAdjust }: { occurrence: Occurrence; onAdjust: () => void }) {
  const f = useFormat()
  const lookups = useLookups()
  const post = usePostOccurrence()
  const skip = useSkipOccurrence()
  const offset = daysBetween(todayIso(), occurrence.date)
  const due = occurrence.status === 'DUE'
  return (
    <li className={ROW}>
      <FlowIcon flow={occurrence} icon={lookups.icon(occurrence.categoryId)} />
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-medium">
        <span>{occurrence.name}</span>
        {due ? <Badge variant="warning">سررسید شده</Badge> : occurrence.mode === 'AUTO' ? <Badge variant="outline"><Zap className="size-3.5" />خودکار</Badge> : null}
      </p>
      <FlowAmount flow={occurrence} account={lookups.account.get(occurrence.accountId)} />
      <p className={cn(ROW_DETAILS, 'text-xs text-muted-foreground')}>
        {f.date(occurrence.date)} ({formatDayOffset(offset, f.prefs.digits)}) | {accountsText(occurrence, lookups)}
      </p>
      <div className={ROW_ACTIONS}>
        <Button size="sm" variant={due ? 'default' : 'outline'} loading={post.isPending}
          onClick={() => post.mutate({ ruleId: occurrence.ruleId, date: occurrence.date }, { onSuccess: () => toast.success(`«${occurrence.name}» ثبت شد.`) })}>
          ثبت
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${occurrence.name}`}><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={onAdjust}><SlidersHorizontal />ثبت با مبلغ یا تاریخ دیگر</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => skip.mutate({ ruleId: occurrence.ruleId, date: occurrence.date }, {
              onSuccess: () => toast.success('این نوبت رد شد.', {
                action: { label: 'بازگرداندن', onClick: () => skip.mutate({ ruleId: occurrence.ruleId, date: occurrence.date, undo: true }) },
              }),
            })}><SkipForward />رد کردن این نوبت</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </li>
  )
}

function RuleRow({ rule, onEdit, onDelete }: { rule: RecurringRule; onEdit: () => void; onDelete: () => void }) {
  const f = useFormat()
  const lookups = useLookups()
  const save = useSaveRule()
  const toggle = () => {
    save.mutate({ id: rule.id, ...toRuleInput(rule), active: !rule.active }, {
      onSuccess: () => toast.success(rule.active ? 'متوقف شد؛ نوبت‌های بعدی ثبت یا یادآوری نمی‌شوند.' : 'دوباره فعال شد.'),
      onError: (err) => toast.error(err instanceof ApiError ? err.message : 'تغییر انجام نشد.'),
    })
  }
  return (
    <li className={cn(ROW, !rule.active && 'opacity-60')}>
      <FlowIcon flow={rule} icon={lookups.icon(rule.categoryId)} />
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-medium">
        <span>{rule.name}</span>
        {rule.mode === 'AUTO' ? <Badge variant="outline"><Zap className="size-3.5" />خودکار</Badge> : <Badge variant="outline"><BellRing className="size-3.5" />یادآوری</Badge>}
        {!rule.active ? <Badge variant="secondary">متوقف</Badge> : null}
      </p>
      <FlowAmount flow={rule} account={lookups.account.get(rule.accountId)} />
      <div className={cn(ROW_DETAILS, 'grid gap-0.5 text-xs text-muted-foreground')}>
        <p>{describeRecurrence(rule, f.prefs.digits)} | {accountsText(rule, lookups)}</p>
        {rule.active && rule.nextDate ? (
          <p>
            <CalendarClock className="me-1 inline size-3.5 align-[-3px]" aria-hidden />
            نوبت بعد: {f.date(rule.nextDate)} ({formatDayOffset(daysBetween(todayIso(), rule.nextDate), f.prefs.digits)})
            {rule.dueCount > 0 ? <span className="font-medium text-warning"> | {f.number(rule.dueCount)} نوبت ثبت‌نشده</span> : null}
          </p>
        ) : rule.active ? <p>نوبت دیگری نمانده است.</p> : null}
      </div>
      <div className={ROW_ACTIONS}>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${rule.name}`}><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
            <DropdownMenuItem onSelect={toggle}>{rule.active ? <><Pause />توقف</> : <><Play />فعال‌سازی دوباره</>}</DropdownMenuItem>
            <DropdownMenuItem onSelect={onDelete} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </li>
  )
}

export function RecurringPage() {
  const f = useFormat()
  const { data: rules, isPending } = useRecurringRules()
  const { data: pending = [] } = usePendingOccurrences()
  const remove = useDeleteRule()
  const [editing, setEditing] = useState<{ rule?: RecurringRule } | null>(null)
  const [deleting, setDeleting] = useState<RecurringRule | null>(null)
  const [adjusting, setAdjusting] = useState<Occurrence | null>(null)
  const dueCount = pending.filter((o) => o.status === 'DUE').length

  return (
    <>
      <PageHeader
        title="تراکنش‌های تکراری"
        description="حقوق، اجاره، اشتراک‌ها و هر دریافت یا پرداختی که مرتب تکرار می‌شود"
        actions={<Button onClick={() => setEditing({})}><Plus />تراکنش تکراری جدید</Button>}
      />
      {isPending ? (
        <div className="grid gap-4"><Skeleton className="h-40" /><Skeleton className="h-64" /></div>
      ) : !rules?.length ? (
        <EmptyState
          icon={Repeat}
          title="هنوز تراکنش تکراری ندارید"
          description="حقوق ماهانه، اجاره، شهریه یا اشتراک‌ها را یک بار تعریف کنید؛ کارنامه یا در روز سررسید خودکار ثبتشان می‌کند، یا یادآوری می‌کند تا با یک کلیک ثبت کنید. در پیش‌بینی نقدینگی هم حساب می‌شوند."
          action={<Button onClick={() => setEditing({})}><Plus />تعریف اولین تراکنش تکراری</Button>}
          className="bg-card"
        />
      ) : (
        <div className="grid grid-cols-1 gap-5">
          {pending.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>منتظر ثبت</CardTitle>
                <CardDescription>
                  {dueCount > 0 ? `${f.number(dueCount)} نوبت سررسید شده و منتظر تأیید شماست؛ ` : ''}نوبت‌های هفت روز آینده هم این‌جا هستند.
                </CardDescription>
              </CardHeader>
              <CardContent className="pt-0">
                <ul className="divide-y">
                  {pending.map((o) => <PendingRow key={`${o.ruleId}-${o.date}`} occurrence={o} onAdjust={() => setAdjusting(o)} />)}
                </ul>
              </CardContent>
            </Card>
          ) : null}
          <Card>
            <CardHeader>
              <CardTitle>همه‌ی تراکنش‌های تکراری</CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <ul className="divide-y">
                {rules.map((r) => <RuleRow key={r.id} rule={r} onEdit={() => setEditing({ rule: r })} onDelete={() => setDeleting(r)} />)}
              </ul>
            </CardContent>
          </Card>
        </div>
      )}
      <RecurringRuleDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} rule={editing?.rule} />
      <Dialog open={adjusting !== null} onOpenChange={(o) => !o && setAdjusting(null)}>
        {adjusting ? <PostDialog key={`${adjusting.ruleId}-${adjusting.date}`} occurrence={adjusting} onClose={() => setAdjusting(null)} /> : null}
      </Dialog>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        destructive
        title={`حذف «${deleting?.name ?? ''}»؟`}
        description="تراکنش‌هایی که تا حالا ثبت شده‌اند می‌مانند؛ فقط نوبت‌های بعدی دیگر ثبت یا یادآوری نمی‌شوند."
        confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('تراکنش تکراری حذف شد.'); setDeleting(null) } })}
      />
    </>
  )
}
