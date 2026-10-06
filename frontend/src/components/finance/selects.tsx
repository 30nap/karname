import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectSeparator, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useAccounts } from '@/features/accounts/api'
import { useCategoryTree } from '@/features/categories/api'
import { useCommodities } from '@/features/commodities/api'
import { ACCOUNT_GROUPS, COMMODITY_KIND_LABELS } from '@/lib/labels'
import type { CategoryKind, CommodityKind } from '@/lib/api/types'
import { AccountIcon, CategoryIcon } from './icons'

const NONE = '__none__'

interface BaseProps {
  id?: string
  placeholder?: string
  disabled?: boolean
  'aria-invalid'?: boolean
  'aria-describedby'?: string
}

export function AccountSelect({ value, onChange, exclude, allowNone, ...props }: BaseProps & {
  value: number | null
  onChange: (id: number | null) => void
  exclude?: number | null
  allowNone?: boolean
}) {
  const { data: accounts = [] } = useAccounts()
  const available = accounts.filter((a) => a.id !== exclude)
  return (
    <Select value={value === null ? (allowNone ? NONE : '') : String(value)} onValueChange={(v) => onChange(v === NONE ? null : Number(v))} disabled={props.disabled}>
      <SelectTrigger id={props.id} aria-invalid={props['aria-invalid']} aria-describedby={props['aria-describedby']}>
        <SelectValue placeholder={props.placeholder ?? 'انتخاب حساب'} />
      </SelectTrigger>
      <SelectContent>
        {allowNone ? <SelectItem value={NONE}>همه‌ی حساب‌ها</SelectItem> : null}
        {ACCOUNT_GROUPS.map((group) => {
          const items = available.filter((a) => group.types.includes(a.type))
          if (items.length === 0) return null
          return (
            <SelectGroup key={group.title}>
              <SelectLabel>{group.title}</SelectLabel>
              {items.map((a) => (
                <SelectItem key={a.id} value={String(a.id)}>
                  <span className="flex items-center gap-2"><AccountIcon type={a.type} className="text-muted-foreground" />{a.name}</span>
                </SelectItem>
              ))}
            </SelectGroup>
          )
        })}
      </SelectContent>
    </Select>
  )
}

export function CategorySelect({ value, onChange, kind, allowNone = true, noneLabel = 'بدون دسته‌بندی', ...props }: BaseProps & {
  value: number | null
  onChange: (id: number | null) => void
  kind: CategoryKind
  allowNone?: boolean
  noneLabel?: string
}) {
  const tree = useCategoryTree(kind)
  return (
    <Select value={value === null ? NONE : String(value)} onValueChange={(v) => onChange(v === NONE ? null : Number(v))} disabled={props.disabled}>
      <SelectTrigger id={props.id} aria-invalid={props['aria-invalid']} aria-describedby={props['aria-describedby']}>
        <SelectValue placeholder={props.placeholder ?? 'انتخاب دسته‌بندی'} />
      </SelectTrigger>
      <SelectContent>
        {allowNone ? (
          <>
            <SelectItem value={NONE}>{noneLabel}</SelectItem>
            <SelectSeparator />
          </>
        ) : null}
        {tree.map((root) => (
          <SelectGroup key={root.id}>
            <SelectItem value={String(root.id)}>
              <span className="flex items-center gap-2 font-medium"><CategoryIcon name={root.icon} className="text-muted-foreground" />{root.name}</span>
            </SelectItem>
            {root.children.map((child) => (
              <SelectItem key={child.id} value={String(child.id)} className="ps-12">
                {child.name}
              </SelectItem>
            ))}
          </SelectGroup>
        ))}
      </SelectContent>
    </Select>
  )
}

const KIND_ORDER: CommodityKind[] = ['TOMAN', 'FIAT', 'GOLD', 'COIN', 'CRYPTO', 'SECURITY', 'PROPERTY', 'VEHICLE', 'OTHER']

export function CommoditySelect({ value, onChange, ...props }: BaseProps & { value: string; onChange: (code: string) => void }) {
  const { data: commodities = [] } = useCommodities()
  return (
    <Select value={value} onValueChange={onChange} disabled={props.disabled}>
      <SelectTrigger id={props.id} aria-invalid={props['aria-invalid']} aria-describedby={props['aria-describedby']}>
        <SelectValue placeholder={props.placeholder ?? 'انتخاب واحد'} />
      </SelectTrigger>
      <SelectContent>
        {KIND_ORDER.map((kind) => {
          const items = commodities.filter((c) => c.kind === kind)
          if (items.length === 0) return null
          return (
            <SelectGroup key={kind}>
              <SelectLabel>{COMMODITY_KIND_LABELS[kind]}</SelectLabel>
              {items.map((c) => (
                <SelectItem key={c.code} value={c.code}>{c.nameFa}{c.code !== 'IRT' && c.unitFa !== c.nameFa ? ` (${c.unitFa})` : ''}</SelectItem>
              ))}
            </SelectGroup>
          )
        })}
      </SelectContent>
    </Select>
  )
}
