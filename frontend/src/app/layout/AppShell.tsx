import { LogOut, Menu, Plus, Settings, UserRound } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useLogout, useMe } from '@/features/auth/api'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { TransactionDialogProvider, useTransactionDialog } from '@/features/transactions/TransactionDialog'
import { cn } from '@/lib/cn'
import { ALL_NAV_ITEMS, NAV_GROUPS, type NavItem } from './nav'
import { Logo } from './Logo'

function useVisibleItems(items: NavItem[]) {
  const me = useMe()
  return items.filter((i) => !i.adminOnly || me.role === 'ADMIN')
}

function SidebarLink({ item, onNavigate }: { item: NavItem; onNavigate?: () => void }) {
  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
          isActive ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-accent hover:text-foreground',
        )
      }
    >
      <item.icon className="size-[18px]" />
      {item.label}
    </NavLink>
  )
}

function NavList({ onNavigate }: { onNavigate?: () => void }) {
  const me = useMe()
  return (
    <nav className="flex flex-col gap-4" aria-label="منوی اصلی">
      {NAV_GROUPS.map((group, index) => {
        const items = group.items.filter((i) => !i.adminOnly || me.role === 'ADMIN')
        if (items.length === 0) return null
        return (
          <div key={group.title ?? index} className="flex flex-col gap-0.5">
            {group.title ? <p className="px-3 pb-1 text-xs font-medium text-muted-foreground/80">{group.title}</p> : null}
            {items.map((item) => <SidebarLink key={item.to} item={item} onNavigate={onNavigate} />)}
          </div>
        )
      })}
    </nav>
  )
}

function UserMenu({ compact }: { compact?: boolean }) {
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {compact ? (
          <Button variant="ghost" size="icon" aria-label="حساب کاربری">
            <UserRound />
          </Button>
        ) : (
          <button type="button" className="flex w-full cursor-pointer items-center gap-3 rounded-lg p-2 text-start hover:bg-accent">
            <span className="flex size-9 items-center justify-center rounded-full bg-primary/10 font-semibold text-primary">
              {me.displayName.slice(0, 1)}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium">{me.displayName}</span>
              <span className="block truncate text-xs text-muted-foreground ltr text-start">{me.username}</span>
            </span>
          </button>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align={compact ? 'end' : 'start'}>
        <DropdownMenuLabel>{me.displayName}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => navigate('/settings')}>
          <Settings /> تنظیمات
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => logout.mutate()} className="text-destructive focus:text-destructive">
          <LogOut /> خروج
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function BottomLink({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      className={({ isActive }) =>
        cn('flex flex-1 flex-col items-center justify-center gap-1 text-[11px] font-medium', isActive ? 'text-primary' : 'text-muted-foreground')
      }
    >
      <item.icon className="size-5" />
      {item.label}
    </NavLink>
  )
}

function MobileBottomBar({ onMore }: { onMore: () => void }) {
  const primary = useVisibleItems(ALL_NAV_ITEMS.filter((i) => i.primary))
  const openTransaction = useTransactionDialog()
  const half = Math.ceil(primary.length / 2)
  return (
    <nav aria-label="منوی پایین" className="fixed inset-x-0 bottom-0 z-40 border-t bg-card/95 backdrop-blur safe-bottom lg:hidden">
      <div className="mx-auto flex h-16 max-w-xl items-stretch justify-around">
        {primary.slice(0, half).map((item) => <BottomLink key={item.to} item={item} />)}
        <div className="flex flex-1 items-start justify-center">
          <button
            type="button"
            onClick={() => openTransaction()}
            aria-label="ثبت تراکنش"
            className="-mt-4 flex size-14 cursor-pointer items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg ring-4 ring-background transition-transform active:scale-95"
          >
            <Plus className="size-6" />
          </button>
        </div>
        {primary.slice(half).map((item) => <BottomLink key={item.to} item={item} />)}
        <button type="button" onClick={onMore} className="flex flex-1 cursor-pointer flex-col items-center justify-center gap-1 text-[11px] font-medium text-muted-foreground">
          <Menu className="size-5" />
          بیشتر
        </button>
      </div>
    </nav>
  )
}

function QuickAddButton() {
  const openTransaction = useTransactionDialog()
  return (
    <Button className="w-full" onClick={() => openTransaction()}>
      <Plus />ثبت تراکنش
    </Button>
  )
}

export function AppShell() {
  const [moreOpen, setMoreOpen] = useState(false)
  const location = useLocation()
  const current = ALL_NAV_ITEMS.find((i) => (i.to === '/' ? location.pathname === '/' : location.pathname.startsWith(i.to)))

  return (
    <TransactionDialogProvider>
      <div className="min-h-dvh">
        <aside className="fixed inset-y-0 start-0 z-30 hidden w-64 flex-col border-e bg-card lg:flex">
          <div className="flex h-16 items-center justify-between ps-5 pe-3">
            <Logo />
            <NotificationBell align="start" />
          </div>
          <div className="px-3 pb-2">
            <QuickAddButton />
          </div>
          <div className="flex-1 overflow-y-auto px-3 py-2">
            <NavList />
          </div>
          <div className="border-t p-3">
            <UserMenu />
          </div>
        </aside>

        <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b bg-card/95 px-4 backdrop-blur lg:hidden">
          <Logo compact />
          <span className="text-sm font-semibold">{current?.label}</span>
          <div className="flex items-center">
            <NotificationBell align="end" />
            <UserMenu compact />
          </div>
        </header>

        <main className="lg:ps-64">
          <div className="mx-auto w-full max-w-6xl px-4 pb-28 pt-5 sm:px-6 lg:pb-10 lg:pt-8">
            <Outlet />
          </div>
        </main>

        <MobileBottomBar onMore={() => setMoreOpen(true)} />

        <Dialog open={moreOpen} onOpenChange={setMoreOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>همه‌ی بخش‌ها</DialogTitle>
            </DialogHeader>
            <DialogBody>
              <NavList onNavigate={() => setMoreOpen(false)} />
            </DialogBody>
          </DialogContent>
        </Dialog>
      </div>
    </TransactionDialogProvider>
  )
}
