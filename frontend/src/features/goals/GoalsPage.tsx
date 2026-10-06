import { AlertTriangle, CalendarClock, CheckCircle2, Flag, MoreVertical, Pencil, Plus, Target, Trash2, TrendingUp } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { Input, Textarea } from '@/components/ui/input'
import { PageHeader } from '@/components/ui/page-header'
import { Progress } from '@/components/ui/progress'
import { Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Amount } from '@/components/finance/Amount'
import { CategoryIcon } from '@/components/finance/icons'
import { CATEGORY_ICON_LABELS } from '@/components/finance/category-icons'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { CommoditySelect } from '@/components/finance/selects'
import { usePrefs, useFormat } from '@/app/preferences'
import { useAccounts } from '@/features/accounts/api'
import { useCommodityMap } from '@/features/commodities/api'
import { ApiError } from '@/lib/api/client'
import type { Goal } from '@/lib/api/types'
import { formatMonths } from '@/lib/format/duration'
import { fromDisplayAmount, toDisplayAmount } from '@/lib/format/money'
import { addDaysIso, currentMonthKey, todayIso } from '@/lib/jalali'
import { cn } from '@/lib/cn'
import { useDeleteGoal, useGoals, useSaveGoal } from './api'

const GOAL_ICONS = ['plane', 'house', 'car', 'graduation-cap', 'laptop', 'smartphone', 'heart-pulse', 'baby', 'gift', 'party-popper',
  'briefcase', 'building', 'coins', 'wallet', 'trending-up', 'sparkles']

function TrackBadge({ goal }: { goal: Goal }) {
  if (goal.achieved) return <Badge variant="income"><CheckCircle2 className="size-3.5" />رسیدید!</Badge>
  if (goal.onTrack === true) return <Badge variant="income"><CheckCircle2 className="size-3.5" />طبق برنامه</Badge>
  if (goal.onTrack === false) return <Badge variant="warning"><AlertTriangle className="size-3.5" />عقب از برنامه</Badge>
  return null
}

function GoalCard({ goal, onEdit, onDelete }: { goal: Goal; onEdit: () => void; onDelete: () => void }) {
  const f = useFormat()
  const commodities = useCommodityMap()
  const unit = commodities.get(goal.commodity)
  const progress = goal.progress === null ? null : Number(goal.progress)
  const money = (value: string | null) => <Amount value={value} commodity={goal.commodity} />
  return (
    <Card className={cn(goal.archived && 'opacity-60')}>
      <CardContent className="grid gap-3 p-4 sm:p-5">
        <div className="flex items-start gap-3">
          <span className={cn('flex size-11 shrink-0 items-center justify-center rounded-full', goal.achieved ? 'bg-income/10 text-income' : 'bg-primary/10 text-primary')}>
            <CategoryIcon name={goal.icon ?? 'flag'} className="size-5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="flex flex-wrap items-center gap-2 font-semibold">
              <span className="truncate">{goal.name}</span>
              <TrackBadge goal={goal} />
              {goal.archived ? <Badge variant="outline">بایگانی</Badge> : null}
            </p>
            <p className="text-sm text-muted-foreground">
              {goal.currentAmount !== null ? <>{money(goal.currentAmount)} از </> : null}{money(goal.targetAmount)}
            </p>
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${goal.name}`}><MoreVertical /></Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
              <DropdownMenuItem onSelect={onDelete} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>

        {progress !== null ? (
          <div className="grid gap-1">
            <Progress value={progress} tone={goal.achieved ? 'income' : 'primary'} label={`پیشرفت ${goal.name}`} />
            <div className="flex justify-between text-xs text-muted-foreground">
              <span className="tabular">{f.percent(Math.min(progress, 9.99))}</span>
              {goal.remaining !== null && !goal.achieved ? <span>مانده: {money(goal.remaining)}</span> : null}
            </div>
          </div>
        ) : null}

        {goal.missingPrices ? (
          <Alert variant="warning">
            <AlertTriangle />
            <span>برای محاسبه‌ی پیشرفت، قیمت {unit.nameFa} یا یکی از دارایی‌های متصل لازم است. <Link to="/assets" className="font-medium text-primary hover:underline">ثبت قیمت</Link></span>
          </Alert>
        ) : null}

        <ul className="grid gap-1.5 text-sm">
          {goal.currentToman !== null && goal.commodity !== 'IRT' ? (
            <li className="text-muted-foreground">ارزش فعلی: ≈ <Amount value={goal.currentToman} /></li>
          ) : null}
          {goal.targetDate ? (
            <li className="flex items-center gap-1.5">
              <CalendarClock className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span>
                تا {f.date(goal.targetDate)}
                {goal.monthsLeft !== null && goal.targetDate >= todayIso() ? <span className="text-muted-foreground"> ({formatMonths(goal.monthsLeft, f.prefs.digits)} مانده)</span> : null}
              </span>
            </li>
          ) : null}
          {goal.requiredPerMonth !== null ? (
            <li className="flex items-center gap-1.5">
              <Target className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span>
                پس‌انداز لازم: ماهی {money(goal.requiredPerMonth)}
                {goal.commodity !== 'IRT' && goal.requiredPerMonthToman ? <span className="text-muted-foreground"> (≈ <Amount value={goal.requiredPerMonthToman} compact />)</span> : null}
              </span>
            </li>
          ) : null}
          {goal.monthlyChange !== null && !goal.achieved ? (
            <li className="flex items-center gap-1.5">
              <TrendingUp className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span>
                روند ۶ ماه اخیر: ماهی <Amount value={goal.monthlyChange} commodity={goal.commodity} sign={Number(goal.monthlyChange) >= 0 ? '+' : undefined} />
                {goal.etaMonth && goal.monthsToGoal !== null ? (
                  <span className="text-muted-foreground"> | با این روند: {f.month(goal.etaMonth)} ({formatMonths(goal.monthsToGoal, f.prefs.digits)} دیگر)</span>
                ) : Number(goal.monthlyChange) <= 0 ? (
                  <span className="text-muted-foreground"> | با این روند به هدف نمی‌رسید</span>
                ) : null}
              </span>
            </li>
          ) : null}
        </ul>
      </CardContent>
    </Card>
  )
}

function GoalForm({ goal, onClose }: { goal?: Goal; onClose: () => void }) {
  const prefs = usePrefs()
  const commodities = useCommodityMap()
  const { data: accounts = [] } = useAccounts()
  const save = useSaveGoal()
  const [name, setName] = useState(goal?.name ?? '')
  const [icon, setIcon] = useState<string | null>(goal?.icon ?? 'flag')
  const [commodity, setCommodity] = useState(goal?.commodity ?? 'IRT')
  const [target, setTarget] = useState(goal ? toDisplayAmount(goal.targetAmount, goal.commodity, prefs) : '')
  const [hasDate, setHasDate] = useState(!!goal?.targetDate)
  const [date, setDate] = useState(goal?.targetDate ?? addDaysIso(todayIso(), 365))
  const [source, setSource] = useState<'accounts' | 'manual'>(goal && goal.accountIds.length === 0 ? 'manual' : 'accounts')
  const [accountIds, setAccountIds] = useState<number[]>(goal?.accountIds ?? [])
  const [manual, setManual] = useState(goal?.manualAmount ? toDisplayAmount(goal.manualAmount, goal.commodity, prefs) : '')
  const [notes, setNotes] = useState(goal?.notes ?? '')
  const [archived, setArchived] = useState(goal?.archived ?? false)
  const [error, setError] = useState<string | null>(null)
  const assets = accounts.filter((a) => !a.liability)
  const unit = commodities.get(commodity)

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return setError('نام هدف را وارد کنید.')
    if (!target || Number(target) <= 0) return setError('مبلغ هدف را وارد کنید.')
    if (source === 'accounts' && accountIds.length === 0) return setError('حداقل یک حساب را انتخاب کنید، یا «مقدار دستی» را بزنید.')
    save.mutate({
      id: goal?.id,
      name: name.trim(),
      icon,
      targetAmount: fromDisplayAmount(target, commodity, prefs),
      commodity,
      targetDate: hasDate ? date : null,
      accountIds: source === 'accounts' ? accountIds : [],
      manualAmount: source === 'manual' && manual ? fromDisplayAmount(manual, commodity, prefs) : null,
      notes: notes || null,
      archived,
    }, {
      onSuccess: () => {
        toast.success(goal ? 'هدف ویرایش شد.' : 'هدف ساخته شد.')
        onClose()
      },
      onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
    })
  }

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{goal ? 'ویرایش هدف' : 'هدف جدید'}</DialogTitle>
          <DialogDescription>مثلاً «صندوق مهاجرت: ۱۵٬۰۰۰ یورو» یا «پیش‌پرداخت خانه».</DialogDescription>
        </DialogHeader>
        <DialogBody className="grid gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <FormField label="نام هدف">
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="مثلاً صندوق مهاجرت" />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-[1fr_12rem]">
            <FormField label="مبلغ هدف">
              <MoneyInput value={target} onChange={setTarget} commodity={unit} />
            </FormField>
            <FormField label="واحد">
              <CommoditySelect value={commodity} onChange={(c) => { setCommodity(c); setTarget(''); setManual('') }} />
            </FormField>
          </div>
          <div className="grid gap-2">
            <label className="flex items-center justify-between gap-3 text-sm font-medium">
              تاریخ هدف
              <Switch checked={hasDate} onCheckedChange={setHasDate} aria-label="تاریخ هدف دارد" />
            </label>
            {hasDate ? <JalaliDatePicker value={date} onChange={setDate} aria-label="تاریخ هدف" /> : null}
          </div>
          <div className="grid gap-2">
            <p className="text-sm font-medium">پیشرفت از روی</p>
            <Segmented<'accounts' | 'manual'>
              ariaLabel="منبع پیشرفت"
              value={source}
              onChange={setSource}
              options={[{ value: 'accounts', label: 'موجودی حساب‌ها' }, { value: 'manual', label: 'مقدار دستی' }]}
            />
            {source === 'accounts' ? (
              <div className="max-h-56 overflow-y-auto rounded-xl border p-1">
                {assets.length === 0 ? <p className="p-3 text-sm text-muted-foreground">هنوز حساب دارایی ندارید.</p> : assets.map((a) => (
                  <label key={a.id} className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 hover:bg-accent/60">
                    <Checkbox
                      checked={accountIds.includes(a.id)}
                      onCheckedChange={(v) => setAccountIds(v ? [...accountIds, a.id] : accountIds.filter((id) => id !== a.id))}
                    />
                    <span className="min-w-0 flex-1 truncate text-sm">{a.name}</span>
                    <Amount value={a.balance} commodity={a.commodity} className="text-xs text-muted-foreground" />
                  </label>
                ))}
              </div>
            ) : (
              <FormField label="مقدار پس‌انداز فعلی" hint="هر وقت پس‌انداز کردید، این عدد را به‌روز کنید.">
                <MoneyInput value={manual} onChange={setManual} commodity={unit} />
              </FormField>
            )}
          </div>
          <fieldset className="grid gap-2">
            <legend className="mb-1.5 text-sm font-medium">نماد</legend>
            <div className="grid grid-cols-8 gap-1.5" role="radiogroup" aria-label="نماد">
              {['flag', ...GOAL_ICONS].map((key) => (
                <button key={key} type="button" role="radio" aria-checked={icon === key} aria-label={CATEGORY_ICON_LABELS[key] ?? 'پرچم'}
                  onClick={() => setIcon(key)}
                  className={cn('flex aspect-square cursor-pointer items-center justify-center rounded-lg border transition-colors',
                    icon === key ? 'border-primary bg-primary/10 text-primary' : 'border-transparent bg-muted/60 text-muted-foreground hover:bg-accent')}>
                  <CategoryIcon name={key} className="size-[18px]" />
                </button>
              ))}
            </div>
          </fieldset>
          <FormField label="یادداشت" optional>
            <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={2000} />
          </FormField>
          {goal ? (
            <label className="flex items-center justify-between gap-3 text-sm">
              بایگانی (در فهرست نمایش داده نشود)
              <Switch checked={archived} onCheckedChange={setArchived} />
            </label>
          ) : null}
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={save.isPending}>ذخیره</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

export function GoalsPage() {
  const f = useFormat()
  const [showArchived, setShowArchived] = useState(false)
  const { data: goals, isPending } = useGoals(showArchived)
  const [editing, setEditing] = useState<{ goal?: Goal } | null>(null)
  const [deleting, setDeleting] = useState<Goal | null>(null)
  const remove = useDeleteGoal()
  const thisMonth = currentMonthKey()

  return (
    <>
      <PageHeader
        title="اهداف"
        description={goals?.length ? `${f.number(goals.length)} هدف | پیش‌بینی‌ها بر اساس روند ۶ ماه اخیر تا ${f.month(thisMonth)}` : 'برای پس‌انداز هدف بگذارید و پیشرفتش را دنبال کنید.'}
        actions={<Button onClick={() => setEditing({})}><Plus />هدف جدید</Button>}
      />
      <label className="mb-4 flex w-fit items-center gap-2 text-sm">
        <Switch checked={showArchived} onCheckedChange={setShowArchived} />
        نمایش بایگانی‌شده‌ها
      </label>
      {isPending ? (
        <div className="grid gap-4 md:grid-cols-2"><Skeleton className="h-56" /><Skeleton className="h-56" /></div>
      ) : !goals?.length ? (
        <EmptyState
          icon={Flag}
          title="هنوز هدفی تعریف نکرده‌اید"
          description="مبلغ و واحد هدف (تومان، دلار، یورو، طلا…) را مشخص کنید و حساب‌هایی را که برایش پس‌انداز می‌کنید وصل کنید؛ کارنامه پیشرفت، پس‌انداز ماهانه‌ی لازم و زمان تقریبی رسیدن را حساب می‌کند."
          action={<Button onClick={() => setEditing({})}><Plus />تعریف اولین هدف</Button>}
          className="bg-card"
        />
      ) : (
        <div className="grid items-start gap-4 md:grid-cols-2">
          {goals.map((g) => <GoalCard key={g.id} goal={g} onEdit={() => setEditing({ goal: g })} onDelete={() => setDeleting(g)} />)}
        </div>
      )}
      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        {editing ? <GoalForm key={editing.goal?.id ?? 'new'} goal={editing.goal} onClose={() => setEditing(null)} /> : null}
      </Dialog>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        destructive
        title={`حذف هدف «${deleting?.name ?? ''}»؟`}
        description="حساب‌ها و تراکنش‌ها دست نمی‌خورند."
        confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('هدف حذف شد.'); setDeleting(null) } })}
      />
    </>
  )
}
