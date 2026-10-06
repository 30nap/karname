import { MoreVertical, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { useFormat } from '@/app/preferences'
import { useMe } from '@/features/auth/api'
import type { AdminUser } from '@/lib/api/types'
import { useAdminUsers, useDeleteAdminUser, useResetUserPassword, useSystemSettings, useUpdateAdminUser, useUpdateSystemSettings } from './api'
import { PriceSourcesCard } from './PriceSources'

export function AdminSettings() {
  const me = useMe()
  const f = useFormat()
  const users = useAdminUsers(true)
  const system = useSystemSettings(true)
  const updateSystem = useUpdateSystemSettings()
  const updateUser = useUpdateAdminUser()
  const resetPassword = useResetUserPassword()
  const deleteUser = useDeleteAdminUser()
  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null)
  const [newPassword, setNewPassword] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<AdminUser | null>(null)

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>ثبت‌نام</CardTitle>
          <CardDescription>اگر کارنامه روی اینترنت در دسترس است، بعد از ساخت حساب اعضای خانواده ثبت‌نام را ببند.</CardDescription>
        </CardHeader>
        <CardContent>
          <label className="flex items-center justify-between gap-4">
            <span className="text-sm font-medium">ثبت‌نام کاربر جدید آزاد باشد</span>
            <Switch checked={system.data?.registrationOpen ?? false} disabled={system.isPending}
              onCheckedChange={(v) => updateSystem.mutate({ registrationOpen: v })} />
          </label>
        </CardContent>
      </Card>

      <PriceSourcesCard />

      <Card>
        <CardHeader>
          <CardTitle>کاربران</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-2">
          {users.isPending ? <Skeleton className="h-24" /> : users.data?.map((u) => (
            <div key={u.id} className="flex items-center gap-3 rounded-lg border p-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{u.displayName}</span>
                  <span className="ltr text-xs text-muted-foreground">{u.username}</span>
                  {u.role === 'ADMIN' ? <Badge><ShieldCheck className="size-3" />مدیر</Badge> : null}
                  {!u.enabled ? <Badge variant="expense">غیرفعال</Badge> : null}
                  {u.totpEnabled ? <Badge variant="outline">دومرحله‌ای</Badge> : null}
                </div>
                <p className="text-xs text-muted-foreground">آخرین ورود: {u.lastLoginAt ? f.dateTime(u.lastLoginAt) : '—'}</p>
              </div>
              {u.id !== me.id ? (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon-sm" aria-label="عملیات"><MoreVertical /></Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent>
                    <DropdownMenuItem onSelect={() => updateUser.mutate({ id: u.id, enabled: !u.enabled })}>
                      {u.enabled ? 'غیرفعال کردن' : 'فعال کردن'}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => updateUser.mutate({ id: u.id, role: u.role === 'ADMIN' ? 'USER' : 'ADMIN' })}>
                      {u.role === 'ADMIN' ? 'حذف دسترسی مدیر' : 'مدیر کردن'}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => { setNewPassword(''); setResetTarget(u) }}>تعیین رمز جدید</DropdownMenuItem>
                    <DropdownMenuItem className="text-destructive focus:text-destructive" onSelect={() => setDeleteTarget(u)}>حذف کاربر</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              ) : <Badge variant="secondary">شما</Badge>}
            </div>
          ))}
        </CardContent>
      </Card>

      <Dialog open={!!resetTarget} onOpenChange={(o) => !o && setResetTarget(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>رمز جدید برای {resetTarget?.displayName}</DialogTitle></DialogHeader>
          <DialogBody>
            <FormField label="رمز جدید" hint="حداقل ۸ کاراکتر. کاربر از همه‌ی دستگاه‌ها خارج می‌شود.">
              <Input type="text" className="ltr text-start" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
            </FormField>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setResetTarget(null)}>انصراف</Button>
            <Button disabled={newPassword.length < 8} loading={resetPassword.isPending} onClick={() => resetTarget && resetPassword.mutate(
              { id: resetTarget.id, newPassword }, { onSuccess: () => { toast.success('رمز تغییر کرد.'); setResetTarget(null) } })}>
              ذخیره
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog open={!!deleteTarget} onOpenChange={(o) => !o && setDeleteTarget(null)} destructive
        title={`حذف ${deleteTarget?.displayName ?? ''}؟`} description="همه‌ی داده‌های این کاربر برای همیشه پاک می‌شود." confirmLabel="حذف"
        loading={deleteUser.isPending}
        onConfirm={() => deleteTarget && deleteUser.mutate(deleteTarget.id, { onSuccess: () => setDeleteTarget(null) })} />
    </div>
  )
}
