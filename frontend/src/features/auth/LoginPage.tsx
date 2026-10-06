import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/lib/api/client'
import { AuthLayout } from './AuthLayout'
import { useAuthStatus, useLogin } from './api'

const schema = z.object({
  username: z.string().trim().min(1, 'نام کاربری را وارد کنید.'),
  password: z.string().min(1, 'رمز عبور را وارد کنید.'),
  totpCode: z.string().optional(),
  rememberMe: z.boolean(),
})

type FormValues = z.infer<typeof schema>

export function LoginPage() {
  const { data: status } = useAuthStatus()
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const [needTotp, setNeedTotp] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { username: '', password: '', totpCode: '', rememberMe: true } })

  if (status && !status.hasUsers) return <Navigate to="/register" replace />

  const onSubmit = form.handleSubmit((values) => {
    setError(null)
    login.mutate(
      { ...values, totpCode: needTotp ? values.totpCode : undefined },
      {
        onSuccess: () => navigate((location.state as { from?: string } | null)?.from ?? '/', { replace: true }),
        onError: (e) => {
          if (e instanceof ApiError && e.code === 'auth.totpRequired') {
            setNeedTotp(true)
            return
          }
          setError(e.message)
        },
      },
    )
  })

  return (
    <AuthLayout
      title="ورود به کارنامه"
      subtitle="مدیریت مالی و دارایی شخصی"
      footer={status?.registrationOpen ? (
        <>حساب ندارید؟ <Link to="/register" className="font-medium text-primary hover:underline">ثبت‌نام کنید</Link></>
      ) : null}
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        {needTotp ? (
          <FormField label="کد ورود دومرحله‌ای" error={form.formState.errors.totpCode?.message} hint="کد ۶ رقمی برنامه‌ی احراز هویت یا یکی از کدهای بازیابی">
            <Input inputMode="numeric" autoComplete="one-time-code" autoFocus className="ltr text-center tracking-[0.3em]" {...form.register('totpCode')} />
          </FormField>
        ) : (
          <>
            <FormField label="نام کاربری" error={form.formState.errors.username?.message}>
              <Input autoComplete="username" autoCapitalize="none" spellCheck={false} className="ltr text-start" {...form.register('username')} />
            </FormField>
            <FormField label="رمز عبور" error={form.formState.errors.password?.message}>
              <Input type="password" autoComplete="current-password" className="ltr text-start" {...form.register('password')} />
            </FormField>
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <Controller control={form.control} name="rememberMe" render={({ field }) => (
                <Checkbox checked={field.value} onCheckedChange={(v) => field.onChange(v === true)} />
              )} />
              مرا به خاطر بسپار
            </label>
          </>
        )}
        <Button type="submit" size="lg" loading={login.isPending}>
          {needTotp ? 'تأیید و ورود' : 'ورود'}
        </Button>
        {needTotp ? (
          <Button type="button" variant="link" onClick={() => setNeedTotp(false)}>بازگشت</Button>
        ) : null}
      </form>
    </AuthLayout>
  )
}
