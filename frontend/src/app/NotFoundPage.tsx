import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <div className="flex min-h-[50dvh] flex-col items-center justify-center gap-3 text-center">
      <p className="text-5xl font-bold text-muted-foreground/50">۴۰۴</p>
      <p className="font-semibold">این صفحه پیدا نشد.</p>
      <Button asChild variant="outline"><Link to="/">بازگشت به داشبورد</Link></Button>
    </div>
  )
}
