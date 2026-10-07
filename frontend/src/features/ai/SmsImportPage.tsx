import { MessageSquareText, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { PageHeader } from '@/components/ui/page-header'
import { useFormat } from '@/app/preferences'
import { ApiError } from '@/lib/api/client'
import { AiUnavailable } from './AiUnavailable'
import { DraftList } from './DraftList'
import { useAiStatus, useSmsImport } from './api'

const MAX_TEXT = 20_000

const EXAMPLE = `بانک ملت
برداشت:1,250,000
حساب:***4321
مانده:12,345,678
07/13-12:30

بانک سامان
واریز 50,000,000 ریال
کارت ...5678
1405/07/10`

export function SmsImportPage() {
  const f = useFormat()
  const { data: status } = useAiStatus()
  const sms = useSmsImport()
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const result = sms.data

  const read = () => {
    setError(null)
    sms.mutate(text, { onError: (e) => setError(e instanceof ApiError ? e.message : 'پیامک‌ها بررسی نشدند.') })
  }
  const blocked = status && (!status.enabled || !status.tasks.EXTRACT)

  return (
    <>
      <PageHeader title="ورود پیامک بانکی" description="پیامک‌های بانک را کپی کنید؛ تراکنش‌ها برای تأیید شما آماده می‌شوند." />
      {blocked ? <AiUnavailable status={status} task="EXTRACT" /> : (
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <Card>
            <CardHeader>
              <CardTitle>پیامک‌ها</CardTitle>
              <CardDescription>چند پیامک را با یک خط خالی از هم جدا کنید (حداکثر ۵۰ پیامک).</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3">
              <Textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={MAX_TEXT} rows={12} dir="auto"
                placeholder={EXAMPLE} aria-label="متن پیامک‌ها" className="font-[inherit] text-sm" />
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
                  <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-income" />
                  شماره‌ی کارت، شبا و حساب پیش از ارسال پوشانده می‌شود؛ فقط چهار رقم آخر برای پیدا کردن حساب به کار می‌رود.
                </p>
                <Button onClick={read} loading={sms.isPending} disabled={!text.trim()}><MessageSquareText />بررسی پیامک‌ها</Button>
              </div>
              {error ? <Alert variant="destructive">{error}</Alert> : null}
            </CardContent>
          </Card>
          <div className="grid grid-cols-1 gap-3">
            {result ? (
              <>
                <p className="text-sm text-muted-foreground">
                  {f.number(result.messages)} پیامک بررسی شد | {f.number(result.drafts.length)} تراکنش پیدا شد
                  {result.ignored.length ? ` | ${f.number(result.ignored.length)} پیامک بدون تراکنش` : ''}
                </p>
                {result.drafts.length ? (
                  <DraftList key={result.drafts.map((d) => d.draft.ref).join()} drafts={result.drafts.map((d) => d.draft)}
                    labels={result.drafts.map((d) => `پیامک ${f.number(d.message)}`)} />
                ) : null}
                {result.ignored.length ? (
                  <ul className="grid gap-1 rounded-xl border p-3 text-xs text-muted-foreground">
                    {result.ignored.map((i) => <li key={i.message}>پیامک {f.number(i.message)}: {i.reason}</li>)}
                  </ul>
                ) : null}
              </>
            ) : (
              <div className="rounded-xl border border-dashed p-6 text-center text-sm text-muted-foreground">
                تراکنش‌های پیدا شده این‌جا نمایش داده می‌شوند. حساب هر تراکنش از روی چهار رقم آخر کارت یا حساب (که در تنظیمات حساب
                ثبت کرده‌اید) یا نام بانک پیدا می‌شود؛ پیامکی که دوباره وارد شود، دوباره ثبت نمی‌شود.
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}
