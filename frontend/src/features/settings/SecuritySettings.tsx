import QRCode from 'qrcode'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { useMe } from '@/features/auth/api'
import { toLatinDigits } from '@/lib/persian/digits'
import {
  useChangePassword,
  useDeleteAccount,
  useRevokeOtherSessions,
  useTotpDisable,
  useTotpEnable,
  useTotpSetup,
  useUpdateProfile,
} from './api'

function ProfileCard() {
  const me = useMe()
  const [name, setName] = useState(me.displayName)
  const update = useUpdateProfile()
  return (
    <Card>
      <CardHeader>
        <CardTitle>مشخصات</CardTitle>
        <CardDescription>نام کاربری: <span className="ltr inline-block">{me.username}</span></CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3">
        <FormField label="نام نمایشی" className="min-w-56 flex-1">
          <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
        </FormField>
        <Button variant="outline" loading={update.isPending} disabled={!name.trim() || name === me.displayName}
          onClick={() => update.mutate(name, { onSuccess: () => toast.success('نام ذخیره شد.') })}>
          ذخیره
        </Button>
      </CardContent>
    </Card>
  )
}

function PasswordCard() {
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    setError(null)
    if (next.length < 8) return setError('رمز جدید باید حداقل ۸ کاراکتر باشد.')
    if (next !== confirm) return setError('تکرار رمز جدید یکسان نیست.')
    change.mutate({ currentPassword: current, newPassword: next }, {
      onSuccess: () => {
        toast.success('رمز عبور تغییر کرد و دستگاه‌های دیگر از حساب خارج شدند.')
        setCurrent(''); setNext(''); setConfirm('')
      },
      onError: (e) => setError(e.message),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>تغییر رمز عبور</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-3 sm:grid-cols-3">
        {error ? <Alert variant="destructive" className="sm:col-span-3">{error}</Alert> : null}
        <FormField label="رمز فعلی">
          <Input type="password" autoComplete="current-password" className="ltr text-start" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </FormField>
        <FormField label="رمز جدید">
          <Input type="password" autoComplete="new-password" className="ltr text-start" value={next} onChange={(e) => setNext(e.target.value)} />
        </FormField>
        <FormField label="تکرار رمز جدید">
          <Input type="password" autoComplete="new-password" className="ltr text-start" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </FormField>
        <div className="sm:col-span-3">
          <Button onClick={submit} loading={change.isPending} disabled={!current || !next}>تغییر رمز</Button>
        </div>
      </CardContent>
    </Card>
  )
}

function TwoFactorCard() {
  const me = useMe()
  const setup = useTotpSetup()
  const enable = useTotpEnable()
  const disable = useTotpDisable()
  const [qr, setQr] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [recovery, setRecovery] = useState<string[] | null>(null)
  const [disableOpen, setDisableOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (setup.data) QRCode.toDataURL(setup.data.otpauthUri, { margin: 1, width: 200 }).then(setQr).catch(() => setQr(null))
  }, [setup.data])

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <CardTitle>ورود دومرحله‌ای</CardTitle>
          {me.totpEnabled ? <Badge variant="income">فعال</Badge> : <Badge variant="outline">غیرفعال</Badge>}
        </div>
        <CardDescription>با برنامه‌هایی مثل Google Authenticator یا Aegis، علاوه بر رمز یک کد ۶ رقمی هم لازم می‌شود.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {me.totpEnabled ? (
          <Button variant="outline" className="w-fit" onClick={() => { setError(null); setPassword(''); setDisableOpen(true) }}>غیرفعال کردن</Button>
        ) : setup.data ? (
          <div className="grid gap-4 sm:grid-cols-[auto_1fr] sm:items-start">
            {qr ? <img src={qr} alt="کد QR ورود دومرحله‌ای" className="size-48 rounded-lg border bg-white p-2" /> : null}
            <div className="grid gap-3">
              <p className="text-sm">کد QR را با برنامه‌ی احراز هویت اسکن کن یا این کلید را دستی وارد کن:</p>
              <code className="ltr block break-all rounded-lg bg-muted px-3 py-2 text-xs">{setup.data.secret}</code>
              {error ? <Alert variant="destructive">{error}</Alert> : null}
              <FormField label="کد ۶ رقمی برنامه">
                <Input inputMode="numeric" className="ltr max-w-40 text-center tracking-[0.3em]" value={code} onChange={(e) => setCode(e.target.value)} />
              </FormField>
              <FormField label="رمز عبور فعلی" hint="برای اطمینان از این‌که خودت هستی.">
                <Input type="password" autoComplete="current-password" className="max-w-64" value={password}
                  onChange={(e) => setPassword(e.target.value)} />
              </FormField>
              <Button className="w-fit" loading={enable.isPending} disabled={!code.trim() || !password} onClick={() => {
                setError(null)
                enable.mutate({ code: toLatinDigits(code), password }, {
                  onSuccess: (r) => { setPassword(''); setRecovery(r.recoveryCodes) },
                  onError: (e) => setError(e.message),
                })
              }}>فعال‌سازی</Button>
            </div>
          </div>
        ) : (
          <Button className="w-fit" loading={setup.isPending} onClick={() => setup.mutate()}>راه‌اندازی</Button>
        )}
      </CardContent>

      <Dialog open={!!recovery} onOpenChange={(open) => !open && setRecovery(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>کدهای بازیابی</DialogTitle>
            <DialogDescription>اگر گوشی‌ات در دسترس نبود، با هر کدام از این کدها یک بار می‌توانی وارد شوی. جای امنی نگهشان دار.</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="ltr grid grid-cols-2 gap-2 font-mono text-sm">
              {recovery?.map((c) => <code key={c} className="rounded-md bg-muted px-2 py-1 text-center">{c}</code>)}
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => { navigator.clipboard?.writeText(recovery?.join('\n') ?? ''); toast.success('کپی شد.') }}>کپی</Button>
            <Button onClick={() => setRecovery(null)}>ذخیره کردم</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={disableOpen} onOpenChange={setDisableOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>غیرفعال کردن ورود دومرحله‌ای</DialogTitle>
          </DialogHeader>
          <DialogBody className="grid gap-3">
            {error ? <Alert variant="destructive">{error}</Alert> : null}
            <FormField label="رمز عبور">
              <Input type="password" className="ltr text-start" value={password} onChange={(e) => setPassword(e.target.value)} />
            </FormField>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDisableOpen(false)}>انصراف</Button>
            <Button variant="destructive" loading={disable.isPending} onClick={() => disable.mutate(password, {
              onSuccess: () => { setDisableOpen(false); setup.reset(); toast.success('ورود دومرحله‌ای غیرفعال شد.') },
              onError: (e) => setError(e.message),
            })}>غیرفعال کن</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function SessionsAndDangerCard() {
  const revoke = useRevokeOtherSessions()
  const remove = useDeleteAccount()
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  return (
    <Card>
      <CardHeader>
        <CardTitle>نشست‌ها و حذف حساب</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-3">
        <Button variant="outline" loading={revoke.isPending} onClick={() => revoke.mutate(undefined, { onSuccess: () => toast.success('از همه‌ی دستگاه‌های دیگر خارج شدی.') })}>
          خروج از دستگاه‌های دیگر
        </Button>
        <Button variant="destructive" onClick={() => { setPassword(''); setError(null); setOpen(true) }}>حذف حساب و همه‌ی داده‌ها</Button>
      </CardContent>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>حذف دائمی حساب</DialogTitle>
            <DialogDescription>همه‌ی حساب‌ها، تراکنش‌ها و تنظیماتت برای همیشه پاک می‌شود و قابل بازگشت نیست. پیشنهاد می‌کنیم قبلش از بخش «ورود و خروج داده» نسخه‌ی پشتیبان بگیری.</DialogDescription>
          </DialogHeader>
          <DialogBody className="grid gap-3">
            {error ? <Alert variant="destructive">{error}</Alert> : null}
            <FormField label="برای تأیید، رمز عبورت را وارد کن">
              <Input type="password" className="ltr text-start" value={password} onChange={(e) => setPassword(e.target.value)} />
            </FormField>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>انصراف</Button>
            <Button variant="destructive" disabled={!password} loading={remove.isPending}
              onClick={() => remove.mutate(password, { onError: (e) => setError(e.message) })}>حذف کن</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

export function SecuritySettings() {
  return (
    <div className="grid grid-cols-1 gap-4">
      <ProfileCard />
      <PasswordCard />
      <TwoFactorCard />
      <SessionsAndDangerCard />
    </div>
  )
}
