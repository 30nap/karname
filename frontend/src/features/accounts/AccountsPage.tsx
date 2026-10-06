import { AlertTriangle, Archive, Plus, WalletCards } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHeader } from '@/components/ui/page-header'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Tooltip } from '@/components/ui/tooltip'
import { Amount } from '@/components/finance/Amount'
import { AccountIcon } from '@/components/finance/icons'
import { useCommodityMap } from '@/features/commodities/api'
import { useFormat } from '@/app/preferences'
import type { Account } from '@/lib/api/types'
import { ACCOUNT_GROUPS, ACCOUNT_TYPE_LABELS, BANK_NAMES } from '@/lib/labels'
import { sumAmounts } from '@/lib/format/money'
import { cn } from '@/lib/cn'
import { useAccounts } from './api'
import { AccountFormDialog } from './AccountFormDialog'

function AccountCard({ account }: { account: Account }) {
  const commodities = useCommodityMap()
  const commodity = commodities.get(account.commodity)
  const sub = [account.bank ? `بانک ${BANK_NAMES[account.bank]}` : null, account.counterparty, commodity.code !== 'IRT' ? commodity.nameFa : null]
    .filter(Boolean).join(' | ')
  return (
    <Link
      to={`/accounts/${account.id}`}
      className={cn('flex items-center gap-3 rounded-xl border bg-card p-3 transition-colors hover:bg-accent/50', account.archived && 'opacity-60')}
    >
      <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-full', account.liability ? 'bg-expense/10 text-expense' : 'bg-primary/10 text-primary')}>
        <AccountIcon type={account.type} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5">
          <span className="truncate font-medium">{account.name}</span>
          {account.archived ? <Archive className="size-3.5 text-muted-foreground" aria-label="بایگانی" /> : null}
        </span>
        <span className="block truncate text-xs text-muted-foreground">{sub || ACCOUNT_TYPE_LABELS[account.type]}</span>
      </span>
      <span className="flex shrink-0 flex-col items-end">
        <Amount value={account.liability ? account.balance.replace(/^-/, '') : account.balance} commodity={account.commodity}
          tone={account.liability ? 'expense' : 'neutral'} className="font-semibold" />
        {account.commodity !== 'IRT' ? (
          account.priced ? (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              {account.priceStale ? (
                <Tooltip content="قیمت این دارایی قدیمی است؛ از صفحه‌ی دارایی‌ها به‌روزش کنید.">
                  <AlertTriangle className="size-3 text-warning" aria-label="قیمت قدیمی" />
                </Tooltip>
              ) : null}
              ≈ <Amount value={account.valueToman} />
            </span>
          ) : (
            <span className="text-xs text-warning">قیمت ثبت نشده</span>
          )
        ) : null}
      </span>
    </Link>
  )
}

export function AccountsPage() {
  const f = useFormat()
  const [showArchived, setShowArchived] = useState(false)
  const { data: accounts, isPending } = useAccounts(showArchived)
  const [dialog, setDialog] = useState(false)

  const assets = accounts?.filter((a) => !a.liability && a.includeInNetWorth && a.valueToman) ?? []
  const liabilities = accounts?.filter((a) => a.liability && a.includeInNetWorth && a.valueToman) ?? []
  const totalAssets = sumAmounts(assets.map((a) => a.valueToman!))
  const totalLiabilities = sumAmounts(liabilities.map((a) => a.valueToman!.replace(/^-/, '')))

  return (
    <>
      <PageHeader
        title="حساب‌ها"
        description={accounts?.length ? `${f.number(accounts.length)} حساب` : undefined}
        actions={<Button onClick={() => setDialog(true)}><Plus />حساب جدید</Button>}
      />
      {isPending ? (
        <div className="grid gap-3">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-16" />)}</div>
      ) : !accounts?.length ? (
        <EmptyState icon={WalletCards} title="هنوز حسابی نساخته‌اید"
          description="حساب بانکی، پول نقد، طلا، ارز، رمزارز یا حتی وام‌هایتان را اضافه کنید تا دارایی خالص‌تان محاسبه شود."
          action={<Button onClick={() => setDialog(true)}><Plus />ساخت اولین حساب</Button>} />
      ) : (
        <div className="grid gap-6">
          <Card>
            <CardContent className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-3">
              <div>
                <p className="text-xs text-muted-foreground">جمع دارایی‌ها</p>
                <Amount value={totalAssets} className="text-lg font-bold" />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">جمع بدهی‌ها</p>
                <Amount value={totalLiabilities} tone={Number(totalLiabilities) > 0 ? 'expense' : 'neutral'} className="text-lg font-bold" />
              </div>
              <label className="col-span-2 flex items-center gap-2 self-center text-sm sm:col-span-1 sm:justify-end">
                <Switch checked={showArchived} onCheckedChange={setShowArchived} />
                نمایش بایگانی‌شده‌ها
              </label>
            </CardContent>
          </Card>
          {ACCOUNT_GROUPS.map((group) => {
            const items = accounts.filter((a) => group.types.includes(a.type))
            if (items.length === 0) return null
            return (
              <section key={group.title} aria-labelledby={`group-${group.title}`}>
                <h2 id={`group-${group.title}`} className="mb-2 text-sm font-semibold text-muted-foreground">{group.title}</h2>
                <div className="grid gap-2 md:grid-cols-2">
                  {items.map((a) => <AccountCard key={a.id} account={a} />)}
                </div>
              </section>
            )
          })}
        </div>
      )}
      <AccountFormDialog open={dialog} onOpenChange={setDialog} />
    </>
  )
}
