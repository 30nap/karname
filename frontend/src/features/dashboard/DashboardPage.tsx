import { PageHeader } from '@/components/ui/page-header'
import { useMe } from '@/features/auth/api'
import { useFormat } from '@/app/preferences'
import { todayIso } from '@/lib/jalali'
import { formatJalaliWithWeekday } from '@/lib/jalali'

export function DashboardPage() {
  const me = useMe()
  const f = useFormat()
  return (
    <>
      <PageHeader title={`سلام ${me.displayName}`} description={formatJalaliWithWeekday(todayIso(), f.prefs.digits)} />
    </>
  )
}
