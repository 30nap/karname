import { AlertOctagon, AlertTriangle, MoreVertical, Pencil, PiggyBank, Plus, Sparkles, Trash2, TrendingUp } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { PageHeader } from '@/components/ui/page-header'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { MonthNavigator } from '@/components/finance/MonthNavigator'
import { CategorySelect } from '@/components/finance/selects'
import { usePrefs, useFormat } from '@/app/preferences'
import { useCategories } from '@/features/categories/api'
import { ApiError } from '@/lib/api/client'
import type { BudgetItem } from '@/lib/api/types'
import { fromDisplayAmount, IRT, toDisplayAmount } from '@/lib/format/money'
import { currentMonthKey } from '@/lib/jalali'
import { cn } from '@/lib/cn'
import { useBudgetMonth, useBudgetSuggestions, useRemoveBudget, useSetBudget } from './api'

function StatusLabel({ item }: { item: BudgetItem }) {
  if (item.status === 'OVER') {
    return (
      <span className="flex items-center gap-1 text-xs font-medium text-expense">
        <AlertOctagon className="size-3.5" aria-hidden />
        <Amount value={item.remaining.replace(/^-/, '')} /> بیشتر از بودجه
      </span>
    )
  }
  if (item.status === 'WARNING') {
    return (
      <span className="flex items-center gap-1 text-xs font-medium text-warning">
        <AlertTriangle className="size-3.5" aria-hidden />
        نزدیک سقف | <Amount value={item.remaining} /> مانده
      </span>
    )
  }
  return <span className="text-xs text-muted-foreground"><Amount value={item.remaining} /> مانده</span>
}

function BudgetRow({ item, month, onEdit, onRemove }: {
  item: BudgetItem
  month: string
  onEdit: () => void
  onRemove: () => void
}) {
  const f = useFormat()
  const ratio = Number(item.ratio)
  const projectedOver = item.projected !== null && Number(item.projected) > Number(item.amount)
  return (
    <li className="grid gap-2 py-3">
      <div className="flex items-center gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <CategoryIcon name={item.icon} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-2 font-medium">
            <span className="truncate">{item.name}</span>
            {item.parentName ? <span className="truncate text-xs font-normal text-muted-foreground">{item.parentName}</span> : null}
            {!item.recurring ? <Badge variant="outline">فقط این ماه</Badge> : null}
          </p>
          <p className="text-xs text-muted-foreground">
            <Amount value={item.spent} /> از <Amount value={item.amount} />
            <span className="tabular"> | {f.percent(ratio)}</span>
          </p>
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های بودجه‌ی ${item.name}`}><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
            <DropdownMenuItem onSelect={onRemove} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <Progress value={ratio} tone="auto" label={`${item.name}: ${f.percent(ratio)} بودجه`} />
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <StatusLabel item={item} />
        {item.projected !== null ? (
          <span className={cn('flex items-center gap-1 text-xs', projectedOver ? 'text-warning' : 'text-muted-foreground')}>
            <TrendingUp className="size-3.5" aria-hidden />
            پیش‌بینی تا آخر ماه: <Amount value={item.projected} compact />
          </span>
        ) : null}
        {item.since !== month ? <span className="text-xs text-muted-foreground">از {f.month(item.since)}</span> : null}
      </div>
      {item.unpricedCount > 0 ? (
        <p className="text-xs text-warning">{f.number(item.unpricedCount)} هزینه به‌خاطر نبود قیمت در این جمع نیامده است.</p>
      ) : null}
    </li>
  )
}

type Editing = { item?: BudgetItem } | null

function BudgetDialog({ month, editing, onClose, budgeted }: { month: string; editing: Editing; onClose: () => void; budgeted: Set<number> }) {
  return (
    <Dialog open={editing !== null} onOpenChange={(open) => !open && onClose()}>
      {editing ? <BudgetForm key={editing.item?.categoryId ?? 'new'} month={month} item={editing.item} onClose={onClose} budgeted={budgeted} /> : null}
    </Dialog>
  )
}

function BudgetForm({ month, item, onClose, budgeted }: { month: string; item?: BudgetItem; onClose: () => void; budgeted: Set<number> }) {
  const prefs = usePrefs()
  const f = useFormat()
  const save = useSetBudget()
  const suggestions = useBudgetSuggestions(month)
  const { data: categories = [] } = useCategories()
  const [categoryId, setCategoryId] = useState<number | null>(item?.categoryId ?? null)
  const [amount, setAmount] = useState(item ? toDisplayAmount(item.amount, 'IRT', prefs) : '')
  const [recurring, setRecurring] = useState(item?.recurring ?? true)
  const [error, setError] = useState<string | null>(null)

  const rootOf = (id: number | null) => {
    const c = categories.find((x) => x.id === id)
    return c?.parentId ?? c?.id ?? null
  }
  const suggestion = suggestions.data?.find((s) => s.categoryId === rootOf(categoryId))
  const duplicate = !item && categoryId !== null && budgeted.has(categoryId)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (categoryId === null) return setError('دسته‌بندی را انتخاب کنید.')
    if (!amount || Number(amount) <= 0) return setError('مبلغ بودجه را وارد کنید.')
    save.mutate({ month, categoryId, amount: fromDisplayAmount(amount, 'IRT', prefs), recurring }, {
      onSuccess: () => {
        toast.success('بودجه ذخیره شد.')
        onClose()
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{item ? `بودجه‌ی ${item.name}` : 'بودجه‌ی جدید'}</DialogTitle>
          <DialogDescription>سقف هزینه‌ی ماهانه برای یک دسته؛ بودجه‌ی دسته‌ی اصلی، زیرمجموعه‌هایش را هم شامل می‌شود.</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          {item ? null : (
            <FormField label="دسته‌بندی" error={duplicate ? 'این دسته در این ماه بودجه دارد؛ با ذخیره، مبلغش عوض می‌شود.' : undefined}>
              <CategorySelect kind="EXPENSE" value={categoryId} onChange={setCategoryId} allowNone={false} />
            </FormField>
          )}
          <FormField label="سقف ماهانه" hint={suggestion ? (
            <button type="button" className="cursor-pointer text-primary hover:underline"
              onClick={() => setAmount(toDisplayAmount(suggestion.suggestedToman, 'IRT', prefs))}>
              میانگین ۳ ماه اخیر {suggestion.name}: {f.money(suggestion.averageToman, IRT)} — استفاده از {f.money(suggestion.suggestedToman, IRT, { compact: true })}
            </button>
          ) : undefined}>
            <MoneyInput value={amount} onChange={setAmount} commodity={IRT} showQuickButtons autoFocus={!!item} />
          </FormField>
          <label className="flex items-center justify-between gap-3 rounded-xl border p-3 text-sm">
            <span>
              <span className="block font-medium">تکرار در ماه‌های بعد</span>
              <span className="text-xs text-muted-foreground">خاموش: فقط برای {f.month(month)}</span>
            </span>
            <Switch checked={recurring} onCheckedChange={setRecurring} />
          </label>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={save.isPending}>ذخیره</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function RemoveDialog({ month, item, onClose }: { month: string; item: BudgetItem | null; onClose: () => void }) {
  const f = useFormat()
  const remove = useRemoveBudget()
  const run = (scope: 'MONTH' | 'FORWARD') =>
    item && remove.mutate({ month, categoryId: item.categoryId, scope }, {
      onSuccess: () => {
        toast.success('بودجه حذف شد.')
        onClose()
      },
    })
  return (
    <Dialog open={item !== null} onOpenChange={(open) => !open && onClose()}>
      {item ? (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>حذف بودجه‌ی {item.name}</DialogTitle>
            <DialogDescription>بودجه‌ی ماه‌های گذشته دست نمی‌خورد.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={onClose}>انصراف</Button>
            <Button variant="outline" loading={remove.isPending} onClick={() => run('MONTH')}>فقط {f.month(month)}</Button>
            <Button variant="destructive" loading={remove.isPending} onClick={() => run('FORWARD')}>از {f.month(month)} به بعد</Button>
          </DialogFooter>
        </DialogContent>
      ) : null}
    </Dialog>
  )
}

function SuggestionsDialog({ month, open, onClose }: { month: string; open: boolean; onClose: () => void }) {
  const f = useFormat()
  const suggestions = useBudgetSuggestions(month, open)
  const save = useSetBudget()
  const [selected, setSelected] = useState<Set<number> | null>(null)
  const list = suggestions.data ?? []
  // By default, offer the categories that have no budget yet.
  const chosen = selected ?? new Set(list.filter((s) => s.currentBudget === null).map((s) => s.categoryId))
  const [saving, setSaving] = useState(false)

  const apply = async () => {
    setSaving(true)
    try {
      for (const s of list.filter((x) => chosen.has(x.categoryId))) {
        await save.mutateAsync({ month, categoryId: s.categoryId, amount: s.suggestedToman, recurring: true })
      }
      toast.success('بودجه‌های پیشنهادی ثبت شد.')
      setSelected(null)
      onClose()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'ثبت بودجه‌ها ناموفق بود.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) { setSelected(null); onClose() } }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>پیشنهاد بودجه</DialogTitle>
          <DialogDescription>بر اساس میانگین هزینه‌ی ماهانه‌ی شما در سه ماه گذشته، رو به بالا گرد شده.</DialogDescription>
        </DialogHeader>
        <DialogBody>
          {suggestions.isPending ? <Skeleton className="h-40" /> : list.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">برای پیشنهاد بودجه، به هزینه‌های دسته‌بندی‌شده‌ی چند ماه گذشته نیاز است.</p>
          ) : (
            <ul className="divide-y">
              {list.map((s) => (
                <li key={s.categoryId}>
                  <label className="flex cursor-pointer items-center gap-3 py-2.5">
                    <Checkbox
                      checked={chosen.has(s.categoryId)}
                      onCheckedChange={(v) => {
                        const next = new Set(chosen)
                        if (v) next.add(s.categoryId)
                        else next.delete(s.categoryId)
                        setSelected(next)
                      }}
                    />
                    <CategoryIcon name={s.icon} className="text-muted-foreground" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{s.name}</span>
                      <span className="block text-xs text-muted-foreground">
                        میانگین: {f.money(s.averageToman, IRT)}
                        {s.currentBudget ? ` | بودجه‌ی فعلی: ${f.money(s.currentBudget, IRT)}` : ''}
                      </span>
                    </span>
                    <Amount value={s.suggestedToman} className="text-sm font-semibold" />
                  </label>
                </li>
              ))}
            </ul>
          )}
        </DialogBody>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>انصراف</Button>
          <Button disabled={chosen.size === 0} loading={saving} onClick={apply}>
            ثبت {f.number(chosen.size)} بودجه
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function BudgetsPage() {
  const f = useFormat()
  const [month, setMonth] = useState(currentMonthKey())
  const { data, isPending } = useBudgetMonth(month)
  const [editing, setEditing] = useState<Editing>(null)
  const [removing, setRemoving] = useState<BudgetItem | null>(null)
  const [suggestOpen, setSuggestOpen] = useState(false)
  const items = data?.items ?? []
  const totalRatio = data && Number(data.totalBudget) > 0 ? Number(data.totalSpent) / Number(data.totalBudget) : 0
  const daysLeft = data?.current ? data.daysInMonth - data.daysElapsed : null

  return (
    <>
      <PageHeader
        title="بودجه"
        description="سقف هزینه‌ی ماهانه برای هر دسته و وضعیت خرج‌کردن نسبت به آن"
        actions={
          <>
            <Button variant="outline" onClick={() => setSuggestOpen(true)}><Sparkles />پیشنهاد بودجه</Button>
            <Button onClick={() => setEditing({})}><Plus />بودجه‌ی جدید</Button>
          </>
        }
      />
      <MonthNavigator value={month} onChange={setMonth} className="mb-4 sm:w-72" />
      {isPending ? (
        <div className="grid gap-3"><Skeleton className="h-28" /><Skeleton className="h-64" /></div>
      ) : !data || items.length === 0 ? (
        <EmptyState
          icon={PiggyBank}
          title={`برای ${f.month(month)} بودجه‌ای تعریف نشده`}
          description="برای دسته‌های پرهزینه سقف ماهانه بگذارید تا وقتی به ۸۰٪ یا بیشتر رسید باخبر شوید. «پیشنهاد بودجه» از روی هزینه‌های سه ماه اخیر شروع خوبی است."
          action={
            <div className="flex flex-wrap justify-center gap-2">
              <Button variant="outline" onClick={() => setSuggestOpen(true)}><Sparkles />پیشنهاد بودجه</Button>
              <Button onClick={() => setEditing({})}><Plus />بودجه‌ی جدید</Button>
            </div>
          }
          className="bg-card"
        />
      ) : (
        <div className="grid gap-4">
          <Card>
            <CardContent className="grid gap-3 p-4 sm:p-5">
              <div className="flex flex-wrap items-end justify-between gap-2">
                <div>
                  <p className="text-sm text-muted-foreground">خرج‌شده از کل بودجه</p>
                  <p className="text-2xl font-bold">
                    <Amount value={data.totalSpent} withUnit={false} />
                    <span className="text-base font-normal text-muted-foreground"> از <Amount value={data.totalBudget} /></span>
                  </p>
                </div>
                {daysLeft !== null ? <p className="text-sm text-muted-foreground">{f.number(daysLeft)} روز تا پایان ماه</p> : null}
              </div>
              <Progress value={totalRatio} tone="auto" label="خرج‌شده از کل بودجه" />
              {Number(data.unbudgetedSpent) > 0 ? (
                <p className="text-xs text-muted-foreground">
                  به‌علاوه‌ی <Amount value={data.unbudgetedSpent} /> هزینه در دسته‌های بدون بودجه (جمع هزینه‌ی ماه: <Amount value={data.totalExpense} />)
                </p>
              ) : null}
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 pt-1 sm:p-5 sm:pt-2">
              <ul className="divide-y">
                {items.map((item) => (
                  <BudgetRow key={item.categoryId} item={item} month={month} onEdit={() => setEditing({ item })} onRemove={() => setRemoving(item)} />
                ))}
              </ul>
            </CardContent>
          </Card>
        </div>
      )}
      <BudgetDialog month={month} editing={editing} onClose={() => setEditing(null)} budgeted={new Set(items.map((i) => i.categoryId))} />
      <RemoveDialog month={month} item={removing} onClose={() => setRemoving(null)} />
      <SuggestionsDialog month={month} open={suggestOpen} onClose={() => setSuggestOpen(false)} />
    </>
  )
}
