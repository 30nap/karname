import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router'
import { z } from 'zod'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { toLatinDigits } from '@/lib/persian/digits'
import { AuthLayout } from './AuthLayout'
import { useAuthStatus, useRegister } from './api'

const schema = z
  .object({
    displayName: z.string().trim().min(1, 'نام خود را وارد کنید.').max(100, 'نام طولانی است.'),
    username: z
      .string()
      .trim()
      .transform((v) => toLatinDigits(v).toLowerCase())
      .pipe(z.string().regex(/^[a-z0-9][a-z0-9_.-]{2,31}$/, 'نام کاربری باید ۳ تا ۳۲ کاراکتر و فقط حروف انگلیسی، عدد، نقطه، خط تیره یا زیرخط باشد.')),
    password: z.string().min(8, 'رمز عبور باید حداقل ۸ کاراکتر باشد.').max(128, 'رمز عبور طولانی است.'),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'تکرار رمز عبور یکسان نیست.' })

type FormInput = z.input<typeof schema>
type FormOutput = z.output<typeof schema>

export function RegisterPage() {
  const { data: status } = useAuthStatus()
  const register = useRegister()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(schema),
    defaultValues: { displayName: '', username: '', password: '', confirm: '' },
  })

  if (status && status.hasUsers && !status.registrationOpen) return <Navigate to="/login" replace />
  const firstRun = status ? !status.hasUsers : false

  const onSubmit = form.handleSubmit((values) => {
    setError(null)
    register.mutate(
      { displayName: values.displayName, username: values.username, password: values.password, rememberMe: true },
      { onSuccess: () => navigate('/', { replace: true }), onError: (e) => setError(e.message) },
    )
  })

  const errors = form.formState.errors
  return (
    <AuthLayout
      title={firstRun ? 'راه‌اندازی کارنامه' : 'ساخت حساب کاربری'}
      subtitle={firstRun ? 'اولین حساب، مدیر سیستم خواهد بود.' : 'برای شروع، یک حساب بسازید.'}
      footer={firstRun ? null : <>حساب دارید؟ <Link to="/login" className="font-medium text-primary hover:underline">وارد شوید</Link></>}
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        <FormField label="نام نمایشی" error={errors.displayName?.message}>
          <Input autoComplete="name" placeholder="مثلاً سینا" {...form.register('displayName')} />
        </FormField>
        <FormField label="نام کاربری" error={errors.username?.message} hint="با حروف انگلیسی؛ برای ورود استفاده می‌شود.">
          <Input autoComplete="username" autoCapitalize="none" spellCheck={false} className="ltr text-start" {...form.register('username')} />
        </FormField>
        <FormField label="رمز عبور" error={errors.password?.message}>
          <Input type="password" autoComplete="new-password" className="ltr text-start" {...form.register('password')} />
        </FormField>
        <FormField label="تکرار رمز عبور" error={errors.confirm?.message}>
          <Input type="password" autoComplete="new-password" className="ltr text-start" {...form.register('confirm')} />
        </FormField>
        <Button type="submit" size="lg" loading={register.isPending}>
          {firstRun ? 'ساخت حساب مدیر' : 'ثبت‌نام'}
        </Button>
      </form>
    </AuthLayout>
  )
}
