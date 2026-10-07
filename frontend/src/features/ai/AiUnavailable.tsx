import { Sparkles } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { useAuthStatus } from '@/features/auth/api'
import type { AiStatus, AiTask } from '@/lib/api/types'

/** Why an AI feature cannot be used, and what to do about it; null when it can. */
export function AiUnavailable({ status, task, className }: { status: AiStatus | undefined; task: AiTask; className?: string }) {
  const { data } = useAuthStatus()
  const admin = data?.user?.role === 'ADMIN'
  if (!status) return null
  if (!status.enabled) {
    return (
      <EmptyState icon={Sparkles} className={className} title="دستیار هوش مصنوعی خاموش است"
        description="می‌توانید آن را در تنظیمات، بخش «هوش مصنوعی»، روشن کنید."
        action={<Button asChild variant="outline"><Link to="/settings">رفتن به تنظیمات</Link></Button>} />
    )
  }
  if (!status.tasks[task]) {
    return (
      <EmptyState icon={Sparkles} className={className} title="هوش مصنوعی هنوز راه‌اندازی نشده"
        description={admin
          ? 'در تنظیمات مدیریت یک سرویس‌دهنده (مثلاً Claude یا یک مدل محلی Ollama) اضافه کنید و مدل این بخش را مشخص کنید.'
          : 'مدیر کارنامه هنوز سرویس هوش مصنوعی را برای این بخش تنظیم نکرده است.'}
        action={admin ? <Button asChild variant="outline"><Link to="/settings?tab=ai">تنظیم سرویس هوش مصنوعی</Link></Button> : undefined} />
    )
  }
  return null
}
