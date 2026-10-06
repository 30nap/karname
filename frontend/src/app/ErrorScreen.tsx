import { CloudOff } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ErrorScreen({ onRetry, message }: { onRetry?: () => void; message?: string }) {
  return (
    <div className="flex min-h-[60dvh] flex-col items-center justify-center gap-4 p-6 text-center">
      <CloudOff className="size-10 text-muted-foreground" />
      <div>
        <p className="font-semibold">ارتباط با سرور برقرار نشد</p>
        <p className="text-sm text-muted-foreground">{message ?? 'اتصال اینترنت یا وضعیت سرور را بررسی کنید و دوباره تلاش کنید.'}</p>
      </div>
      {onRetry ? <Button onClick={onRetry}>تلاش دوباره</Button> : null}
    </div>
  )
}
