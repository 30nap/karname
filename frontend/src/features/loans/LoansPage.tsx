import { AlertTriangle, CalendarClock, Landmark, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHeader } from '@/components/ui/page-header'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { Amount } from '@/components/finance/Amount'
import { useFormat } from '@/app/preferences'
import type { Loan } from '@/lib/api/types'
import { formatDayOffset } from '@/lib/format/duration'
import { sumAmounts } from '@/lib/format/money'
import { daysBetween, todayIso } from '@/lib/jalali'
import { BANK_NAMES, LOAN_METHOD_LABELS } from '@/lib/labels'
import { useLoans } from './api'
import { InstallmentBadge } from './InstallmentBadge'
import { LoanFormDialog } from './LoanFormDialog'

function LoanCard({ loan }: { loan: Loan }) {
  const f = useFormat()
  const done = loan.next === null
  const rate = Number(loan.annualRate)
  return (
    <Link to={`/loans/${loan.id}`} className="block rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40">
      <Card className="h-full transition-colors hover:border-primary/40">
        <CardContent className="grid grid-cols-1 gap-3 p-4 sm:p-5">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Landmark className="size-5" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="flex flex-wrap items-center gap-2 font-semibold">
                <span className="min-w-0 break-words">{loan.name}</span>
                {done ? <Badge variant="income">تسویه‌شده</Badge> : null}
                {loan.overdueCount > 0 ? (
                  <Badge variant="expense"><AlertTriangle className="size-3.5" />{f.number(loan.overdueCount)} قسط معوق</Badge>
                ) : null}
              </p>
              <p className="text-sm text-muted-foreground">
                {loan.bank ? `${BANK_NAMES[loan.bank]} | ` : ''}{rate === 0 ? 'بدون سود' : `سود ${f.number(loan.annualRate)}٪`} | {LOAN_METHOD_LABELS[loan.method]}
              </p>
            </div>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">مانده‌ی وام</p>
            <Amount value={loan.outstanding} className="text-xl font-bold" />
          </div>
          <div className="grid gap-1">
            <Progress value={loan.paidCount / loan.termMonths} tone={done ? 'income' : 'primary'} label={`اقساط پرداخت‌شده‌ی ${loan.name}`} />
            <p className="text-xs text-muted-foreground tabular">{f.number(loan.paidCount)} از {f.number(loan.termMonths)} قسط پرداخت شده</p>
          </div>
          {loan.next ? (
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
              <CalendarClock className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <span>قسط {f.number(loan.next.number)}:</span>
              <Amount value={loan.next.amount} className="font-medium" />
              <span className="text-muted-foreground">
                {f.date(loan.next.dueDate)} ({formatDayOffset(daysBetween(todayIso(), loan.next.dueDate), f.prefs.digits)})
              </span>
              {loan.next.status === 'OVERDUE' || loan.next.status === 'DUE_SOON' ? <InstallmentBadge status={loan.next.status} /> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </Link>
  )
}

function Summary({ loans }: { loans: Loan[] }) {
  const f = useFormat()
  const active = loans.filter((l) => l.next !== null)
  const monthly = sumAmounts(active.map((l) => l.next!.amount))
  const overdue = loans.filter((l) => l.overdueCount > 0)
  const overdueCount = overdue.reduce((n, l) => n + l.overdueCount, 0)
  return (
    <div className="mb-5 grid gap-3">
      {overdueCount > 0 ? (
        <Alert variant="destructive">
          <AlertTriangle />
          <span>
            {f.number(overdueCount)} قسط معوق به مبلغ <Amount value={sumAmounts(overdue.map((l) => l.overdueAmount))} className="font-semibold" /> دارید
            {overdue.length === 1 ? <> در <Link to={`/loans/${overdue[0].id}`} className="font-medium text-primary hover:underline">{overdue[0].name}</Link></> : null}.
          </span>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">مانده‌ی کل وام‌ها</p>
            <Amount value={sumAmounts(loans.map((l) => l.outstanding))} className="text-xl font-bold" />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">اقساط ماهانه</p>
            <Amount value={monthly} className="text-xl font-bold" />
            <p className="text-xs text-muted-foreground">{f.number(active.length)} وام فعال</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">سود باقی‌مانده</p>
            <Amount value={sumAmounts(loans.map((l) => l.remainingInterest))} className="text-xl font-bold" />
            <p className="text-xs text-muted-foreground">تا پایان همه‌ی اقساط</p>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

export function LoansPage() {
  const f = useFormat()
  const { data: loans, isPending } = useLoans()
  const [creating, setCreating] = useState(false)

  return (
    <>
      <PageHeader
        title="وام‌ها"
        description={loans?.length ? `${f.number(loans.length)} وام | جدول اقساط، پرداخت و یادآوری سررسید` : 'وام‌های بانکی و قرض‌الحسنه با جدول اقساط'}
        actions={<Button onClick={() => setCreating(true)}><Plus />وام جدید</Button>}
      />
      {isPending ? (
        <div className="grid gap-4 md:grid-cols-2"><Skeleton className="h-56" /><Skeleton className="h-56" /></div>
      ) : !loans?.length ? (
        <EmptyState
          icon={Landmark}
          title="هنوز وامی ثبت نکرده‌اید"
          description="مبلغ، نرخ سود و تعداد اقساط را وارد کنید تا جدول اقساط ساخته شود. با پرداخت هر قسط، اصل آن از مانده‌ی وام کم و سودش هزینه ثبت می‌شود؛ سررسیدها هم یادآوری می‌شوند."
          action={<Button onClick={() => setCreating(true)}><Plus />ثبت اولین وام</Button>}
          className="bg-card"
        />
      ) : (
        <>
          <Summary loans={loans} />
          <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-2">
            {loans.map((l) => <LoanCard key={l.id} loan={l} />)}
          </div>
        </>
      )}
      <LoanFormDialog open={creating} onOpenChange={setCreating} />
    </>
  )
}
