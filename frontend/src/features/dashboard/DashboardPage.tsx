import { ArrowDownLeft, ArrowUpRight, ChevronLeft, PiggyBank, Plus, ReceiptText, WalletCards } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHeader } from '@/components/ui/page-header'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { useFormat } from '@/app/preferences'
import { useMe } from '@/features/auth/api'
import { useAccounts } from '@/features/accounts/api'
import { AccountFormDialog } from '@/features/accounts/AccountFormDialog'
import { useDashboard } from '@/features/transactions/api'
import { TransactionRow } from '@/features/transactions/TransactionList'
import { useTransactionDialog } from '@/features/transactions/TransactionDialog'
import type { MonthSummary } from '@/lib/api/types'
import { formatJalaliWithWeekday, todayIso } from '@/lib/jalali'
import { cn } from '@/lib/cn'
import { NetWorthCard } from './NetWorthCard'
import { NetWorthTrend } from './NetWorthTrend'
import { BudgetWidget, GoalsWidget, TopCategoriesWidget } from './PlanningWidgets'

function StatTile({ label, icon: Icon, iconClass, value, footer }: {
  label: string
  icon: typeof PiggyBank
  iconClass: string
  value: React.ReactNode
  footer?: React.ReactNode
}) {
  return (
    <Card>
      <CardContent className="grid gap-1.5 p-4 sm:p-5">
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <span className={cn('flex size-7 items-center justify-center rounded-full', iconClass)}><Icon className="size-4" /></span>
          {label}
        </p>
        <div className="text-2xl font-bold">{value}</div>
        {footer ? <div className="text-xs text-muted-foreground">{footer}</div> : null}
      </CardContent>
    </Card>
  )
}

function MonthTiles({ current, previous }: { current: MonthSummary; previous: MonthSummary }) {
  const f = useFormat()
  const rate = current.savingsRate === null ? null : Number(current.savingsRate)
  return (
    <section aria-labelledby="month-heading" className="grid gap-3">
      <h2 id="month-heading" className="text-sm font-semibold text-muted-foreground">{f.month(current.month)} تا امروز</h2>
      <div className="grid gap-3 sm:grid-cols-3">
        <StatTile
          label="درآمد"
          icon={ArrowDownLeft}
          iconClass="bg-income/10 text-income"
          value={<Amount value={current.incomeToman} />}
          footer={<>{f.month(previous.month)}: <Amount value={previous.incomeToman} /></>}
        />
        <StatTile
          label="هزینه"
          icon={ArrowUpRight}
          iconClass="bg-expense/10 text-expense"
          value={<Amount value={current.expenseToman} />}
          footer={<>{f.month(previous.month)}: <Amount value={previous.expenseToman} /></>}
        />
        <StatTile
          label="پس‌انداز"
          icon={PiggyBank}
          iconClass="bg-primary/10 text-primary"
          value={<Amount value={current.netToman} tone={Number(current.netToman) < 0 ? 'expense' : 'neutral'} />}
          footer={rate === null ? 'هنوز درآمدی ثبت نشده' : `نرخ پس‌انداز: ${f.percent(rate)} درآمد`}
        />
      </div>
      {current.unpricedCount > 0 ? (
        <p className="text-xs text-warning">
          {f.number(current.unpricedCount)} تراکنش ارزی یا طلا به‌خاطر نبود قیمت در این جمع‌ها حساب نشده است.
        </p>
      ) : null}
    </section>
  )
}

function Onboarding({ onCreateAccount }: { onCreateAccount: () => void }) {
  return (
    <EmptyState
      icon={WalletCards}
      title="به کارنامه خوش آمدید"
      description="اول حساب‌هایتان را بسازید: حساب بانکی، پول نقد، طلا، ارز یا رمزارز. موجودی فعلی هر حساب را وارد کنید تا دارایی خالص‌تان محاسبه شود؛ بعد درآمدها و هزینه‌ها را ثبت کنید."
      action={<Button onClick={onCreateAccount}><Plus />ساخت اولین حساب</Button>}
      className="bg-card"
    />
  )
}

export function DashboardPage() {
  const me = useMe()
  const f = useFormat()
  const openTransaction = useTransactionDialog()
  const { data: accounts, isPending: accountsPending } = useAccounts()
  const { data, isPending } = useDashboard()
  const [accountDialog, setAccountDialog] = useState(false)
  const empty = !accountsPending && accounts?.length === 0

  return (
    <>
      <PageHeader
        title={`سلام ${me.displayName}`}
        description={formatJalaliWithWeekday(todayIso(), f.prefs.digits)}
        actions={empty ? null : <Button onClick={() => openTransaction()} className="hidden lg:inline-flex"><Plus />ثبت تراکنش</Button>}
      />
      {isPending || accountsPending ? (
        <div className="grid gap-4">
          <Skeleton className="h-64" />
          <div className="grid gap-3 sm:grid-cols-3">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-28" />)}</div>
        </div>
      ) : empty ? (
        <Onboarding onCreateAccount={() => setAccountDialog(true)} />
      ) : data ? (
        <div className="grid grid-cols-1 gap-5">
          {/* min-w-0 + minmax(0,1fr) columns: charts measure their parent, so tracks must not grow to fit them. */}
          <div className="grid grid-cols-1 gap-5 xl:grid-cols-5">
            <div className="min-w-0 xl:col-span-3"><NetWorthCard netWorth={data.netWorth} /></div>
            <div className="min-w-0 xl:col-span-2"><NetWorthTrend /></div>
          </div>
          <MonthTiles current={data.currentMonth} previous={data.previousMonth} />
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
            <TopCategoriesWidget />
            <BudgetWidget />
            <GoalsWidget />
          </div>
          <Card>
            <CardHeader className="flex-row items-center justify-between">
              <CardTitle>آخرین تراکنش‌ها</CardTitle>
              <Button asChild variant="ghost" size="sm">
                <Link to="/transactions">همه<ChevronLeft /></Link>
              </Button>
            </CardHeader>
            <CardContent className="pt-0">
              {data.recentTransactions.length === 0 ? (
                <EmptyState
                  icon={ReceiptText}
                  title="هنوز تراکنشی ثبت نکرده‌اید"
                  action={<Button onClick={() => openTransaction()}><Plus />ثبت اولین تراکنش</Button>}
                  className="border-none"
                />
              ) : (
                <div className="-mx-2 divide-y">
                  {data.recentTransactions.map((t) => <TransactionRow key={t.id} t={t} showDate />)}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      ) : null}
      <AccountFormDialog open={accountDialog} onOpenChange={setAccountDialog} />
    </>
  )
}
