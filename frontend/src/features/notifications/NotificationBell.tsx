import { AlertOctagon, AlertTriangle, Bell, CheckCheck, Info } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Skeleton } from '@/components/ui/skeleton'
import { useFormat } from '@/app/preferences'
import type { AppNotification, NotificationSeverity } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatTimeAgo } from '@/lib/format/duration'
import { useMarkRead, useNotificationCount, useNotifications } from './api'

const SEVERITY: Record<NotificationSeverity, { icon: typeof Info; className: string; label: string }> = {
  CRITICAL: { icon: AlertOctagon, className: 'bg-destructive/10 text-destructive', label: 'فوری' },
  WARNING: { icon: AlertTriangle, className: 'bg-warning/15 text-warning', label: 'هشدار' },
  INFO: { icon: Info, className: 'bg-primary/10 text-primary', label: 'اطلاع' },
}

function NotificationItem({ item, onOpen }: { item: AppNotification; onOpen: () => void }) {
  const f = useFormat()
  const { icon: Icon, className, label } = SEVERITY[item.severity]
  return (
    <li>
      <button type="button" onClick={onOpen}
        className={cn('flex w-full cursor-pointer items-start gap-3 rounded-lg p-2 text-start transition-colors hover:bg-accent/60', item.read && 'opacity-70')}>
        <span className={cn('mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full', className)} aria-hidden>
          <Icon className="size-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-start gap-2">
            <span className={cn('flex-1 text-sm', item.read ? 'font-normal' : 'font-semibold')}>
              <span className="sr-only">{label}{item.read ? '' : '، خوانده‌نشده'}: </span>{item.title}
            </span>
            {item.read ? null : <span className="mt-1.5 size-2 shrink-0 rounded-full bg-primary" aria-hidden />}
          </span>
          {item.body ? <span className="block text-xs leading-5 text-muted-foreground">{item.body}</span> : null}
          <span className="block text-[11px] text-muted-foreground/80">{formatTimeAgo(item.createdAt, f.prefs.digits)}</span>
        </span>
      </button>
    </li>
  )
}

/** Bell with the unread count; the list loads when it opens. Alignment is logical (RTL: start = right edge). */
export function NotificationBell({ align = 'end' }: { align?: 'start' | 'end' }) {
  const f = useFormat()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const { data: count } = useNotificationCount()
  const { data: items, isPending } = useNotifications(open)
  const markRead = useMarkRead()
  const unread = count?.unread ?? 0

  const openItem = (item: AppNotification) => {
    if (!item.read) markRead.mutate(item.id)
    setOpen(false)
    if (item.link) navigate(item.link)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label={unread > 0 ? `اعلان‌ها، ${f.number(unread)} خوانده‌نشده` : 'اعلان‌ها'}>
          <Bell />
          {unread > 0 ? (
            <span className="absolute end-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-bold leading-none text-destructive-foreground tabular" aria-hidden>
              {unread > 9 ? f.digits('9+') : f.number(unread)}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align={align} className="w-[min(24rem,calc(100vw-2rem))] p-0">
        <div className="flex items-center justify-between border-b px-3 py-2">
          <p className="text-sm font-semibold">اعلان‌ها</p>
          {unread > 0 ? (
            <Button variant="ghost" size="sm" loading={markRead.isPending} onClick={() => markRead.mutate('all')}><CheckCheck />خواندن همه</Button>
          ) : null}
        </div>
        <div className="max-h-[min(28rem,70dvh)] overflow-y-auto p-1">
          {isPending ? (
            <div className="grid gap-2 p-2"><Skeleton className="h-14" /><Skeleton className="h-14" /></div>
          ) : !items?.length ? (
            <p className="px-3 py-8 text-center text-sm text-muted-foreground">اعلانی ندارید. سررسید اقساط و چک‌ها، عبور از بودجه و رسیدن به اهداف این‌جا اطلاع داده می‌شود.</p>
          ) : (
            <ul className="grid grid-cols-1 gap-0.5">
              {items.map((n) => <NotificationItem key={n.id} item={n} onOpen={() => openItem(n)} />)}
            </ul>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}
