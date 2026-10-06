import { Archive, ArchiveRestore, Lock, MoreVertical, Pencil, Plus, Tags, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/ui/empty-state'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { PageHeader } from '@/components/ui/page-header'
import { Select, SelectContent, SelectItem, SelectSeparator, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { CategoryIcon } from '@/components/finance/icons'
import { CATEGORY_ICON_LABELS, CATEGORY_ICONS } from '@/components/finance/category-icons'
import { CategorySelect } from '@/components/finance/selects'
import { ApiError } from '@/lib/api/client'
import type { Category, CategoryKind } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { useFormat } from '@/app/preferences'
import { useCategories, useCategoryTree, useDeleteCategory, useSaveCategory, type CategoryNode } from './api'

const KIND_LABELS: Record<CategoryKind, string> = { EXPENSE: 'هزینه', INCOME: 'درآمد' }
const NO_PARENT = '__root__'

type FormState = { mode: 'create'; kind: CategoryKind; parentId: number | null } | { mode: 'edit'; category: Category }

function CategoryFormDialog({ state, onClose }: { state: FormState | null; onClose: () => void }) {
  return (
    <Dialog open={state !== null} onOpenChange={(open) => !open && onClose()}>
      {state ? <CategoryForm key={state.mode === 'edit' ? state.category.id : `new-${state.kind}-${state.parentId}`} state={state} onClose={onClose} /> : null}
    </Dialog>
  )
}

function CategoryForm({ state, onClose }: { state: FormState; onClose: () => void }) {
  const editing = state.mode === 'edit' ? state.category : null
  const kind = editing?.kind ?? (state.mode === 'create' ? state.kind : 'EXPENSE')
  const { data: all = [] } = useCategories()
  const roots = useCategoryTree(kind)
  const hasChildren = editing ? all.some((c) => c.parentId === editing.id) : false
  const [name, setName] = useState(editing?.name ?? '')
  const [parentId, setParentId] = useState<number | null>(editing ? editing.parentId : state.mode === 'create' ? state.parentId : null)
  const [icon, setIcon] = useState<string | null>(editing?.icon ?? null)
  const [error, setError] = useState<string | null>(null)
  const save = useSaveCategory()

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) {
      setError('نام دسته‌بندی را وارد کنید.')
      return
    }
    save.mutate(
      { id: editing?.id, name: name.trim(), kind, parentId, icon, archived: editing?.archived },
      {
        onSuccess: () => {
          toast.success(editing ? 'دسته‌بندی ویرایش شد.' : 'دسته‌بندی ساخته شد.')
          onClose()
        },
        onError: (err) => setError(err instanceof ApiError ? err.message : 'ذخیره انجام نشد.'),
      },
    )
  }

  const parentOptions = roots.filter((r) => r.id !== editing?.id)

  return (
    <DialogContent>
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{editing ? 'ویرایش دسته‌بندی' : `دسته‌ی ${KIND_LABELS[kind]} جدید`}</DialogTitle>
          {editing?.systemKey ? <DialogDescription>این دسته‌ی سیستمی است؛ می‌توانید نام و نمادش را عوض کنید ولی حذف نمی‌شود.</DialogDescription> : null}
        </DialogHeader>
        <DialogBody className="grid gap-4">
          <FormField label="نام" error={error ?? undefined}>
            <Input value={name} onChange={(e) => { setName(e.target.value); setError(null) }} maxLength={60} autoFocus />
          </FormField>
          <FormField label="دسته‌ی والد" hint={hasChildren ? 'این دسته زیرمجموعه دارد و خودش نمی‌تواند زیرمجموعه‌ی دسته‌ی دیگری شود.' : 'دسته‌بندی‌ها حداکثر دو سطح دارند.'}>
            <Select value={parentId === null ? NO_PARENT : String(parentId)} onValueChange={(v) => setParentId(v === NO_PARENT ? null : Number(v))} disabled={hasChildren}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_PARENT}>— ندارد (دسته‌ی اصلی) —</SelectItem>
                <SelectSeparator />
                {parentOptions.map((r) => (
                  <SelectItem key={r.id} value={String(r.id)}>
                    <span className="flex items-center gap-2"><CategoryIcon name={r.icon} className="text-muted-foreground" />{r.name}</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </FormField>
          <fieldset className="grid gap-2">
            <legend className="mb-1.5 text-sm font-medium">نماد</legend>
            <div className="grid grid-cols-8 gap-1.5 sm:grid-cols-10" role="radiogroup" aria-label="نماد">
              {Object.keys(CATEGORY_ICONS).map((key) => (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={icon === key}
                  aria-label={CATEGORY_ICON_LABELS[key] ?? key}
                  title={CATEGORY_ICON_LABELS[key]}
                  onClick={() => setIcon(icon === key ? null : key)}
                  className={cn(
                    'flex aspect-square cursor-pointer items-center justify-center rounded-lg border transition-colors',
                    icon === key ? 'border-primary bg-primary/10 text-primary' : 'border-transparent bg-muted/60 text-muted-foreground hover:bg-accent',
                  )}
                >
                  <CategoryIcon name={key} className="size-[18px]" />
                </button>
              ))}
            </div>
          </fieldset>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={save.isPending}>ذخیره</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function DeleteCategoryDialog({ category, onClose }: { category: Category | null; onClose: () => void }) {
  const f = useFormat()
  const [reassignTo, setReassignTo] = useState<number | null>(null)
  const remove = useDeleteCategory()
  const { data: all = [] } = useCategories()
  const childCount = category ? all.filter((c) => c.parentId === category.id).length : 0
  const invalid = category !== null && reassignTo !== null && (reassignTo === category.id || all.some((c) => c.id === reassignTo && c.parentId === category.id))

  return (
    <Dialog open={category !== null} onOpenChange={(open) => { if (!open) { setReassignTo(null); onClose() } }}>
      {category ? (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>حذف «{category.name}»</DialogTitle>
            <DialogDescription>
              {childCount > 0 ? `زیرمجموعه‌های این دسته (${f.number(childCount)} مورد) هم حذف می‌شوند. ` : ''}
              تراکنش‌های این دسته حذف نمی‌شوند؛ مشخص کنید به کدام دسته منتقل شوند.
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            <FormField label="انتقال تراکنش‌ها به" error={invalid ? 'نمی‌توانید تراکنش‌ها را به همین دسته یا زیرمجموعه‌اش منتقل کنید.' : undefined}>
              <CategorySelect kind={category.kind} value={reassignTo} onChange={setReassignTo} noneLabel="بدون دسته‌بندی بمانند" />
            </FormField>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={onClose}>انصراف</Button>
            <Button
              variant="destructive"
              disabled={invalid}
              loading={remove.isPending}
              onClick={() => remove.mutate({ id: category.id, reassignTo }, {
                onSuccess: () => {
                  toast.success('دسته‌بندی حذف شد.')
                  setReassignTo(null)
                  onClose()
                },
              })}
            >
              حذف
            </Button>
          </DialogFooter>
        </DialogContent>
      ) : null}
    </Dialog>
  )
}

function CategoryActions({ category, onEdit, onDelete, onAddChild }: {
  category: Category
  onEdit: () => void
  onDelete: () => void
  onAddChild?: () => void
}) {
  const save = useSaveCategory()
  const toggleArchive = () =>
    save.mutate(
      { id: category.id, name: category.name, kind: category.kind, parentId: category.parentId, icon: category.icon, archived: !category.archived },
      {
        onSuccess: () => toast.success(category.archived ? 'دسته‌بندی از بایگانی خارج شد.' : 'دسته‌بندی بایگانی شد؛ در فرم‌ها دیگر نمایش داده نمی‌شود.'),
        onError: (err) => toast.error(err instanceof ApiError ? err.message : 'انجام نشد.'),
      },
    )
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${category.name}`}>
          <MoreVertical />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
        {onAddChild ? <DropdownMenuItem onSelect={onAddChild}><Plus />افزودن زیرمجموعه</DropdownMenuItem> : null}
        <DropdownMenuItem onSelect={toggleArchive}>
          {category.archived ? <><ArchiveRestore />خروج از بایگانی</> : <><Archive />بایگانی</>}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onSelect={onDelete}
          disabled={category.systemKey !== null}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 />حذف
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function CategoryCard({ node, onEdit, onDelete, onAddChild }: {
  node: CategoryNode
  onEdit: (c: Category) => void
  onDelete: (c: Category) => void
  onAddChild: (parent: Category) => void
}) {
  const f = useFormat()
  return (
    <Card className={cn('overflow-hidden', node.archived && 'opacity-60')}>
      <div className="flex items-center gap-3 p-3">
        <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-full', node.kind === 'EXPENSE' ? 'bg-expense/10 text-expense' : 'bg-income/10 text-income')}>
          <CategoryIcon name={node.icon} className="size-[18px]" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-1.5 font-medium">
            <span className="truncate">{node.name}</span>
            {node.systemKey ? <Lock className="size-3.5 shrink-0 text-muted-foreground" aria-label="دسته‌ی سیستمی" /> : null}
            {node.archived ? <Badge variant="outline">بایگانی</Badge> : null}
          </p>
          <p className="text-xs text-muted-foreground">
            {node.children.length > 0 ? `${f.number(node.children.length)} زیرمجموعه` : 'بدون زیرمجموعه'}
          </p>
        </div>
        <CategoryActions category={node} onEdit={() => onEdit(node)} onDelete={() => onDelete(node)} onAddChild={() => onAddChild(node)} />
      </div>
      {node.children.length > 0 ? (
        <ul className="border-t bg-muted/30 px-3 py-1.5">
          {node.children.map((child) => (
            <li key={child.id} className={cn('flex items-center gap-2 py-1 ps-12', child.archived && 'opacity-60')}>
              <CategoryIcon name={child.icon ?? node.icon} className="text-muted-foreground" />
              <span className="min-w-0 flex-1 truncate text-sm">{child.name}</span>
              {child.systemKey ? <Lock className="size-3.5 text-muted-foreground" aria-label="دسته‌ی سیستمی" /> : null}
              {child.archived ? <Badge variant="outline">بایگانی</Badge> : null}
              <CategoryActions category={child} onEdit={() => onEdit(child)} onDelete={() => onDelete(child)} />
            </li>
          ))}
        </ul>
      ) : null}
    </Card>
  )
}

export function CategoriesPage() {
  const [kind, setKind] = useState<CategoryKind>('EXPENSE')
  const [showArchived, setShowArchived] = useState(false)
  const { isPending } = useCategories()
  const tree = useCategoryTree(kind, showArchived)
  const [form, setForm] = useState<FormState | null>(null)
  const [deleting, setDeleting] = useState<Category | null>(null)

  return (
    <>
      <PageHeader
        title="دسته‌بندی‌ها"
        description="درآمدها و هزینه‌هایتان را دسته‌بندی کنید تا گزارش‌ها و بودجه دقیق‌تر شوند."
        actions={<Button onClick={() => setForm({ mode: 'create', kind, parentId: null })}><Plus />دسته‌ی جدید</Button>}
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Tabs value={kind} onValueChange={(v) => setKind(v as CategoryKind)}>
          <TabsList>
            <TabsTrigger value="EXPENSE" className="px-6">هزینه</TabsTrigger>
            <TabsTrigger value="INCOME" className="px-6">درآمد</TabsTrigger>
          </TabsList>
        </Tabs>
        <label className="flex items-center gap-2 text-sm">
          <Switch checked={showArchived} onCheckedChange={setShowArchived} />
          نمایش بایگانی‌شده‌ها
        </label>
      </div>
      {isPending ? (
        <div className="grid gap-3 md:grid-cols-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-20" />)}</div>
      ) : tree.length === 0 ? (
        <EmptyState icon={Tags} title={`دسته‌ی ${KIND_LABELS[kind]}ی ندارید`}
          action={<Button onClick={() => setForm({ mode: 'create', kind, parentId: null })}><Plus />ساخت دسته</Button>} />
      ) : (
        <div className="grid items-start gap-3 md:grid-cols-2">
          {tree.map((node) => (
            <CategoryCard
              key={node.id}
              node={node}
              onEdit={(c) => setForm({ mode: 'edit', category: c })}
              onDelete={setDeleting}
              onAddChild={(parent) => setForm({ mode: 'create', kind: parent.kind, parentId: parent.id })}
            />
          ))}
        </div>
      )}
      <CategoryFormDialog state={form} onClose={() => setForm(null)} />
      <DeleteCategoryDialog category={deleting} onClose={() => setDeleting(null)} />
    </>
  )
}
