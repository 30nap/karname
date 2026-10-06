import { AlertTriangle, CheckCircle2, Clock } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import type { InstallmentStatus } from '@/lib/api/types'
import { INSTALLMENT_STATUS_LABELS } from '@/lib/labels'

/** Installment state as text plus an icon, never color alone. */
export function InstallmentBadge({ status }: { status: InstallmentStatus }) {
  switch (status) {
    case 'PAID':
      return <Badge variant="income"><CheckCircle2 className="size-3.5" />{INSTALLMENT_STATUS_LABELS.PAID}</Badge>
    case 'PAID_BEFORE':
      return <Badge variant="secondary"><CheckCircle2 className="size-3.5" />پیش از ثبت</Badge>
    case 'OVERDUE':
      return <Badge variant="expense"><AlertTriangle className="size-3.5" />{INSTALLMENT_STATUS_LABELS.OVERDUE}</Badge>
    case 'DUE_SOON':
      return <Badge variant="warning"><Clock className="size-3.5" />{INSTALLMENT_STATUS_LABELS.DUE_SOON}</Badge>
    case 'UPCOMING':
      return <Badge variant="outline">{INSTALLMENT_STATUS_LABELS.UPCOMING}</Badge>
  }
}
