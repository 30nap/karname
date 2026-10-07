import { ChevronLeft, ChevronRight, Plus, ReceiptText, Search, Sparkles, Wand2, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { PageHeader } from '@/components/ui/page-header'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { AccountSelect, CategorySelect } from '@/components/finance/selects'
import { Amount } from '@/components/finance/Amount'
import { useFormat } from '@/app/preferences'
import type { TransactionFilter, TransactionType } from '@/lib/api/types'
import { addMonthsToKey, currentMonthKey, monthRange } from '@/lib/jalali'
import { useTransactionPages } from './api'
import { TransactionList } from './TransactionList'
import { useTransactionDialog } from './TransactionDialog'
import { useAiAvailable } from '@/features/ai/api'
import { CategorizeDialog } from '@/features/ai/CategorizeDialog'

type TypeFilter = 'ALL' | 'EXPENSE' | 'INCOME' | 'TRANSFER'

export function TransactionsPage() {
  const f = useFormat()
  const open = useTransactionDialog()
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? currentMonthKey()
  const allTime = params.get('month') === 'all'
  const type = (params.get('type') as TypeFilter | null) ?? 'ALL'
  const accountId = params.get('account') ? Number(params.get('account')) : null
  const categoryId = params.get('category') ? Number(params.get('category')) : null
  const uncategorized = params.get('uncategorized') === '1'
  // A custom range (from reports) replaces the month.
  const rangeFrom = params.get('from')
  const rangeTo = params.get('to')
  const customRange = rangeFrom !== null && rangeTo !== null
  const [search, setSearch] = useState(params.get('q') ?? '')
  const ai = useAiAvailable('EXTRACT')
  const [categorizing, setCategorizing] = useState(false)

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([k, v]) => (v === null || v === '' ? next.delete(k) : next.set(k, v)))
    setParams(next, { replace: true })
  }

  const filter = useMemo<TransactionFilter>(() => {
    const range = customRange ? { start: rangeFrom, end: rangeTo } : allTime ? null : monthRange(month)
    return {
      from: range?.start,
      to: range?.end,
      type: type === 'ALL' ? undefined : [type as TransactionType],
      accountId: accountId ?? undefined,
      categoryId: categoryId ?? undefined,
      uncategorized: uncategorized || undefined,
      q: params.get('q') ?? undefined,
    }
  }, [customRange, rangeFrom, rangeTo, allTime, month, type, accountId, categoryId, uncategorized, params])

  const query = useTransactionPages(filter)
  const pages = query.data?.pages ?? []
  const items = pages.flatMap((p) => p.items)
  const first = pages[0]

  return (
    <>
      <PageHeader
        title="تراکنش‌ها"
        actions={(
          <>
            {ai ? <Button variant="outline" onClick={() => setCategorizing(true)}><Wand2 />دسته‌بندی هوشمند</Button> : null}
            {ai ? <Button variant="outline" onClick={() => open({ text: true })}><Sparkles />ثبت با متن</Button> : null}
            <Button onClick={() => open()}><Plus />تراکنش جدید</Button>
          </>
        )}
      />
      <CategorizeDialog open={categorizing} onOpenChange={setCategorizing} />

      <div className="mb-4 flex flex-col gap-3">
        {/* Mobile: month on its own row, then a two-column grid; desktop: one row. */}
        <div className="grid grid-cols-2 gap-2 lg:flex lg:flex-wrap lg:items-center">
          {customRange ? (
            <div className="col-span-2 flex h-10 items-center justify-between gap-2 rounded-lg border bg-card ps-3">
              <span className="text-sm font-medium">{f.date(rangeFrom)} تا {f.date(rangeTo)}</span>
              <Button variant="ghost" size="icon-sm" aria-label="حذف بازه" onClick={() => update({ from: null, to: null })}><X /></Button>
            </div>
          ) : (
            <div className="col-span-2 flex items-center justify-between rounded-lg border bg-card lg:justify-start">
              <Button variant="ghost" size="icon-sm" aria-label="ماه قبل" onClick={() => update({ month: addMonthsToKey(allTime ? currentMonthKey() : month, -1) })}>
                <ChevronRight />
              </Button>
              <button type="button" className="min-w-28 cursor-pointer px-2 text-sm font-medium" onClick={() => update({ month: allTime ? null : 'all' })}>
                {allTime ? 'همه‌ی زمان‌ها' : f.month(month)}
              </button>
              <Button variant="ghost" size="icon-sm" aria-label="ماه بعد" onClick={() => update({ month: addMonthsToKey(allTime ? currentMonthKey() : month, 1) })}>
                <ChevronLeft />
              </Button>
            </div>
          )}
          <div className="min-w-0 lg:w-36">
            <Select value={type} onValueChange={(v) => update({ type: v === 'ALL' ? null : v, category: null })}>
              <SelectTrigger aria-label="نوع تراکنش"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">همه‌ی انواع</SelectItem>
                <SelectItem value="EXPENSE">هزینه</SelectItem>
                <SelectItem value="INCOME">درآمد</SelectItem>
                <SelectItem value="TRANSFER">انتقال</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="min-w-0 lg:w-44">
            <AccountSelect value={accountId} allowNone placeholder="همه‌ی حساب‌ها" onChange={(id) => update({ account: id ? String(id) : null })} />
          </div>
          {uncategorized ? (
            <div className="col-span-2 flex h-10 items-center justify-between gap-2 rounded-lg border bg-card ps-3 lg:w-48">
              <span className="text-sm">بدون دسته‌بندی</span>
              <Button variant="ghost" size="icon-sm" aria-label="حذف فیلتر بدون دسته‌بندی" onClick={() => update({ uncategorized: null })}><X /></Button>
            </div>
          ) : type !== 'TRANSFER' ? (
            <div className="col-span-2 min-w-0 lg:w-48">
              <CategorySelect kind={type === 'INCOME' ? 'INCOME' : 'EXPENSE'} value={categoryId} noneLabel="همه‌ی دسته‌ها"
                onChange={(id) => update({ category: id ? String(id) : null })} />
            </div>
          ) : null}
          <form className="relative col-span-2 min-w-48 lg:flex-1" onSubmit={(e) => { e.preventDefault(); update({ q: search.trim() || null }) }} role="search">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input value={search} onChange={(e) => setSearch(e.target.value)} onBlur={() => update({ q: search.trim() || null })}
              placeholder="جستجو در شرح یا مبلغ" className="ps-9" aria-label="جستجو" />
          </form>
        </div>
        {first ? (
          <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 text-sm">
            <span className="text-muted-foreground">{f.number(first.total)} تراکنش</span>
            <span className="whitespace-nowrap">درآمد: <Amount value={first.incomeToman} className="font-semibold" /></span>
            <span className="whitespace-nowrap">هزینه: <Amount value={first.expenseToman} className="font-semibold" /></span>
            {first.unpricedCount > 0 ? <span className="text-warning">{f.number(first.unpricedCount)} مورد بدون قیمت</span> : null}
          </div>
        ) : null}
      </div>

      <Card className={query.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
        <CardContent className="p-2 sm:p-3">
          {query.isPending ? (
            <div className="grid gap-3 p-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
          ) : items.length === 0 ? (
            <EmptyState icon={ReceiptText} title="تراکنشی پیدا نشد" description="فیلترها را تغییر دهید یا اولین تراکنش را ثبت کنید."
              action={<Button onClick={() => open()}><Plus />ثبت تراکنش</Button>} className="border-0" />
          ) : (
            <TransactionList items={items} />
          )}
          {query.hasNextPage ? (
            <div className="flex justify-center p-2">
              <Button variant="outline" loading={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>نمایش بیشتر</Button>
            </div>
          ) : null}
        </CardContent>
      </Card>
    </>
  )
}
