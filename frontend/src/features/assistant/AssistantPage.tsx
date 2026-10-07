import { useQueryClient } from '@tanstack/react-query'
import { Check, History, Loader2, MessageSquarePlus, MoreVertical, Pencil, SendHorizontal, Sparkles, Square, Trash2, X } from 'lucide-react'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Input, Textarea } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useFormat } from '@/app/preferences'
import { AiMarkdown } from '@/features/ai/Markdown'
import { AiUnavailable } from '@/features/ai/AiUnavailable'
import { DraftList } from '@/features/ai/DraftList'
import { conversationQuery, stopConversation, useAiStatus, useConversation, useConversations, useDeleteConversation, useRenameConversation } from '@/features/ai/api'
import { ApiError } from '@/lib/api/client'
import type { ChatItem, ConversationSummary } from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatTimeAgo } from '@/lib/format/duration'
import { streamChat, type ChatEvent } from './stream'

const MAX_MESSAGE = 4000

const SUGGESTIONS = [
  'این ماه بیشتر از همه کجا خرج کردم؟',
  'ارزش دارایی‌هایم به دلار در یک سال گذشته چقدر تغییر کرده؟',
  'وضعیت بودجه‌ی این ماه چطور است؟',
  'با این روند، کی به هدف‌هایم می‌رسم؟',
  'تا آخر ماه چه قسط و چکی دارم؟',
  'دیروز ناهار ۱۸۰ تومن خرج کردم، ثبتش کن',
]

/** The turn being written right now, before it is stored. */
interface LiveTurn {
  text: string
  items: ChatItem[]
  notice?: string
  error?: string
}

function applyEvent(turn: LiveTurn, event: ChatEvent): LiveTurn {
  const items = [...turn.items]
  switch (event.type) {
    case 'text': {
      const last = items.at(-1)
      if (last?.kind === 'assistant') items[items.length - 1] = { ...last, text: (last.text ?? '') + event.delta }
      else items.push({ kind: 'assistant', turn: 0, text: event.delta })
      return { ...turn, items }
    }
    case 'tool': {
      const index = items.findLastIndex((i) => i.kind === 'tool' && i.toolId === event.id)
      const item: ChatItem = { kind: 'tool', turn: 0, toolId: event.id, tool: event.name, label: event.label, status: event.status }
      if (index >= 0) items[index] = item
      else items.push(item)
      return { ...turn, items }
    }
    case 'drafts':
      items.push({ kind: 'drafts', turn: 0, toolId: event.toolId, drafts: event.drafts })
      return { ...turn, items }
    case 'notice':
      return { ...turn, notice: event.message }
    case 'error':
      return { ...turn, error: event.message }
    default:
      return turn
  }
}

export function AssistantPage() {
  const params = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: status } = useAiStatus()
  const routeId = params.id ? Number(params.id) : null
  const [conversationId, setConversationId] = useState<number | null>(routeId)
  const [live, setLive] = useState<LiveTurn | null>(null)
  const [failed, setFailed] = useState<{ text: string; error: string } | null>(null)
  const [listOpen, setListOpen] = useState(false)
  const abort = useRef<AbortController | null>(null)
  const idRef = useRef<number | null>(routeId)

  // following a link to another conversation (the list, the browser's back button)
  if (routeId !== conversationId && live === null) {
    setConversationId(routeId)
  }
  useEffect(() => {
    idRef.current = conversationId
  }, [conversationId])

  const conversation = useConversation(conversationId, live !== null)
  const view = conversation.data
  const unavailable = <AiUnavailable status={status} task="CHAT" className="mt-10" />
  const blocked = status && (!status.enabled || !status.tasks.CHAT)

  const send = async (text: string) => {
    const clean = text.trim()
    if (!clean || live) return
    const controller = new AbortController()
    abort.current = controller
    setFailed(null)
    setLive({ text: clean, items: [] })
    let id = conversationId
    let started = false
    try {
      await streamChat({ conversationId: id, text: clean }, (event) => {
        if (event.type === 'start') {
          started = true
          if (event.created) {
            id = event.conversationId
            setConversationId(id)
            idRef.current = id
            // applied at once, so the page never sees the old address with the new conversation
            navigate(`/assistant/${id}`, { replace: true, flushSync: true })
            void queryClient.invalidateQueries({ queryKey: ['ai', 'conversations'] })
          }
        }
        setLive((turn) => (turn ? applyEvent(turn, event) : turn))
      }, controller.signal)
    } catch (e) {
      const aborted = e instanceof DOMException && e.name === 'AbortError'
      // refused before it started (quota, setup…): nothing was stored, so the message stays here with the reason
      if (!started && !aborted) setFailed({ text: clean, error: e instanceof ApiError ? e.message : 'ارسال پیام ناموفق بود.' })
    }
    abort.current = null
    if (started && id !== null) {
      // the stored turn replaces the live one only once it has been read
      await queryClient.fetchQuery({ ...conversationQuery(id), staleTime: 0 }).catch(() => undefined)
      void queryClient.invalidateQueries({ queryKey: ['ai', 'conversations'] })
    }
    void queryClient.invalidateQueries({ queryKey: ['ai', 'status'] })
    setLive(null)
  }

  const stop = () => {
    const id = idRef.current
    if (id !== null) void stopConversation(id).catch(() => abort.current?.abort())
    else abort.current?.abort()
  }

  const startNew = () => {
    if (live) return
    setFailed(null)
    setConversationId(null)
    navigate('/assistant')
    setListOpen(false)
  }

  const list = <ConversationList activeId={conversationId} onPick={() => setListOpen(false)} onNew={startNew} />

  return (
    <div className="grid h-[calc(100dvh-11.5rem)] grid-cols-1 gap-4 lg:h-[calc(100dvh-4.5rem)] lg:grid-cols-[15rem_minmax(0,1fr)]">
      <aside className="hidden min-h-0 flex-col gap-2 lg:flex">{list}</aside>
      <section className="flex min-h-0 min-w-0 flex-col rounded-2xl border bg-card">
        <ChatHeader conversation={view} quota={status?.quota} onOpenList={() => setListOpen(true)} onNew={startNew} busy={live !== null} />
        {blocked ? unavailable : (
          <Messages items={view?.items ?? []} loading={conversation.isLoading && conversationId !== null} live={live} failed={failed}
            empty={conversationId === null} onSuggest={send} />
        )}
        {blocked ? null : (
          <Composer disabled={!!view && (!view.available || view.full)} busy={live !== null} onSend={send} onStop={stop}
            notice={view && !view.available ? 'سرویس یا مدلی که این گفتگو با آن شروع شده دیگر در دسترس نیست؛ گفتگوی تازه‌ای شروع کنید.'
              : view?.full ? 'این گفتگو به حداکثر طول رسیده است؛ برای ادامه گفتگوی تازه‌ای شروع کنید.' : null} />
        )}
      </section>
      <Dialog open={listOpen} onOpenChange={setListOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>گفتگوها</DialogTitle></DialogHeader>
          <DialogBody className="flex flex-col gap-2">{list}</DialogBody>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function ChatHeader({ conversation, quota, onOpenList, onNew, busy }: {
  conversation: { id: number; title: string; model: string } | undefined
  quota: { limit: number; used: number } | undefined
  onOpenList: () => void
  onNew: () => void
  busy: boolean
}) {
  const f = useFormat()
  const navigate = useNavigate()
  const rename = useRenameConversation()
  const remove = useDeleteConversation()
  const [renaming, setRenaming] = useState(false)
  const [title, setTitle] = useState('')
  const [confirm, setConfirm] = useState(false)
  return (
    <div className="flex items-center gap-2 border-b px-3 py-2.5">
      <Button variant="ghost" size="icon" className="lg:hidden" aria-label="گفتگوها" onClick={onOpenList}><History /></Button>
      <div className="min-w-0 flex-1">
        <p className="truncate font-semibold">{conversation?.title ?? 'گفتگوی تازه'}</p>
        <p className="truncate text-xs text-muted-foreground">
          {conversation ? <bdi dir="ltr">{conversation.model}</bdi> : 'دستیار مالی کارنامه'}
          {quota ? ` | امروز ${f.number(quota.used)} از ${f.number(quota.limit)} درخواست` : ''}
        </p>
      </div>
      <Button variant="ghost" size="icon" className="lg:hidden" aria-label="گفتگوی تازه" onClick={onNew} disabled={busy}><MessageSquarePlus /></Button>
      {conversation ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="گزینه‌های گفتگو"><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={() => { setTitle(conversation.title); setRenaming(true) }}><Pencil />تغییر نام</DropdownMenuItem>
            <DropdownMenuItem disabled={busy} onSelect={() => setConfirm(true)} className="text-destructive focus:text-destructive">
              <Trash2 />حذف گفتگو
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
      <Dialog open={renaming} onOpenChange={setRenaming}>
        <DialogContent>
          <DialogHeader><DialogTitle>تغییر نام گفتگو</DialogTitle></DialogHeader>
          <DialogBody>
            <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} autoFocus aria-label="عنوان گفتگو" />
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenaming(false)}>انصراف</Button>
            <Button loading={rename.isPending} disabled={!title.trim()}
              onClick={() => conversation && rename.mutate({ id: conversation.id, title: title.trim() }, { onSuccess: () => setRenaming(false) })}>
              ذخیره
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ConfirmDialog open={confirm} onOpenChange={setConfirm} destructive title="حذف این گفتگو؟"
        description="پیام‌های این گفتگو پاک می‌شود؛ تراکنش‌هایی که از آن ثبت کرده‌اید می‌مانند." confirmLabel="حذف" loading={remove.isPending}
        onConfirm={() => conversation && remove.mutate(conversation.id, {
          onSuccess: () => { setConfirm(false); toast.success('گفتگو حذف شد.'); navigate('/assistant') },
        })} />
    </div>
  )
}

function ConversationList({ activeId, onPick, onNew }: { activeId: number | null; onPick: () => void; onNew: () => void }) {
  const f = useFormat()
  const navigate = useNavigate()
  const { data, isLoading } = useConversations()
  return (
    <>
      <Button variant="outline" className="w-full" onClick={onNew}><MessageSquarePlus />گفتگوی تازه</Button>
      <div className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto">
        {isLoading ? [0, 1, 2].map((i) => <Skeleton key={i} className="h-12 rounded-lg" />) : null}
        {data?.length === 0 ? <p className="px-2 py-4 text-center text-xs text-muted-foreground">هنوز گفتگویی ندارید.</p> : null}
        {data?.map((c: ConversationSummary) => (
          <button key={c.id} type="button" onClick={() => { navigate(`/assistant/${c.id}`); onPick() }}
            className={cn('grid w-full cursor-pointer grid-cols-1 rounded-lg px-3 py-2 text-start transition-colors hover:bg-accent',
              c.id === activeId && 'bg-primary/10 text-primary hover:bg-primary/10')}>
            <span className="truncate text-sm font-medium">{c.title}</span>
            <span className="truncate text-xs text-muted-foreground">
              {formatTimeAgo(c.updatedAt, f.prefs.digits)}{c.available ? '' : ' | فقط خواندنی'}
            </span>
          </button>
        ))}
      </div>
    </>
  )
}

function Messages({ items, loading, live, failed, empty, onSuggest }: {
  items: ChatItem[]
  loading: boolean
  live: LiveTurn | null
  failed: { text: string; error: string } | null
  empty: boolean
  onSuggest: (text: string) => void
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)
  const liveLength = live ? live.items.reduce((n, i) => n + (i.text?.length ?? 0) + 1, 0) : 0
  useEffect(() => {
    const el = scroller.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [items.length, liveLength, live?.error, failed])

  const groups = group(items)
  const showWelcome = empty && !live && !failed
  return (
    <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto px-3 py-4 sm:px-5"
      onScroll={(e) => {
        const el = e.currentTarget
        pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
      }}>
      {loading ? <div className="grid gap-3"><Skeleton className="ms-auto h-10 w-2/3 rounded-2xl" /><Skeleton className="h-24 w-full rounded-2xl" /></div> : null}
      {showWelcome ? <Welcome onSuggest={onSuggest} /> : null}
      <div className="grid grid-cols-1 gap-4">
        {groups.map((g, index) => g.kind === 'user'
          ? <UserBubble key={index} text={g.items[0].text ?? ''} />
          : <AssistantBlock key={index} items={g.items} />)}
        {live ? (
          <>
            <UserBubble text={live.text} />
            <AssistantBlock items={live.items} writing={!live.error} notice={live.notice} error={live.error} />
          </>
        ) : null}
        {failed ? (
          <>
            <UserBubble text={failed.text} />
            <Alert variant="destructive"><X />{failed.error}</Alert>
          </>
        ) : null}
      </div>
    </div>
  )
}

/** User messages stand alone; everything else up to the next one is the assistant's reply. */
function group(items: ChatItem[]): { kind: 'user' | 'assistant'; items: ChatItem[] }[] {
  const groups: { kind: 'user' | 'assistant'; items: ChatItem[] }[] = []
  for (const item of items) {
    if (item.kind === 'user') groups.push({ kind: 'user', items: [item] })
    else if (groups.at(-1)?.kind === 'assistant') groups.at(-1)!.items.push(item)
    else groups.push({ kind: 'assistant', items: [item] })
  }
  return groups
}

function Welcome({ onSuggest }: { onSuggest: (text: string) => void }) {
  return (
    <div className="mx-auto flex max-w-xl flex-col items-center gap-4 py-6 text-center">
      <div className="flex size-14 items-center justify-center rounded-2xl bg-primary/10 text-primary"><Sparkles className="size-7" /></div>
      <div className="space-y-1">
        <h2 className="text-lg font-bold">سلام! دستیار مالی شما هستم</h2>
        <p className="text-sm text-muted-foreground">
          درباره‌ی هزینه‌ها، دارایی‌ها، بودجه و اهدافتان بپرسید. ارقام را از خود کارنامه می‌خوانم و بدون تأیید شما چیزی ثبت نمی‌کنم.
        </p>
      </div>
      <div className="grid w-full grid-cols-1 gap-2 sm:grid-cols-2">
        {SUGGESTIONS.map((s) => (
          <button key={s} type="button" onClick={() => onSuggest(s)}
            className="cursor-pointer rounded-xl border px-3 py-2.5 text-start text-sm transition-colors hover:border-primary/40 hover:bg-primary/5">
            {s}
          </button>
        ))}
      </div>
    </div>
  )
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-ee-md bg-primary px-4 py-2.5 text-sm leading-7 text-primary-foreground">
        {text}
      </p>
    </div>
  )
}

function AssistantBlock({ items, writing, notice, error }: { items: ChatItem[]; writing?: boolean; notice?: string; error?: string }) {
  const hasText = items.some((i) => i.kind === 'assistant' && i.text)
  // drafts close the answer, under the text that explains them
  const drafts = items.filter((i) => i.kind === 'drafts' && i.drafts?.length)
  return (
    <div className="flex min-w-0 flex-col gap-2">
      {items.map((item, index) => {
        switch (item.kind) {
          case 'assistant':
            return item.text ? <AiMarkdown key={index}>{item.text}</AiMarkdown> : null
          case 'tool':
            return <ToolChip key={index} item={item} />
          case 'error':
            return <Alert key={index} variant="destructive"><X />{item.text}</Alert>
          default:
            return null
        }
      })}
      {writing && !hasText ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 className="size-4 animate-spin" />در حال بررسی…</p>
      ) : null}
      {drafts.map((item, index) => <DraftList key={index} drafts={item.drafts ?? []} />)}
      {notice ? <Alert variant="warning">{notice}</Alert> : null}
      {error ? <Alert variant="destructive"><X />{error}</Alert> : null}
    </div>
  )
}

function ToolChip({ item }: { item: ChatItem }) {
  return (
    <span className={cn('inline-flex w-fit items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs text-muted-foreground',
      item.status === 'error' && 'border-destructive/30 text-destructive')}>
      {item.status === 'running' ? <Loader2 className="size-3.5 animate-spin" /> : item.status === 'error' ? <X className="size-3.5" />
        : <Check className="size-3.5 text-income" />}
      {item.label ?? item.tool}
    </span>
  )
}

function Composer({ disabled, busy, onSend, onStop, notice }: {
  disabled: boolean
  busy: boolean
  onSend: (text: string) => void
  onStop: () => void
  notice: string | null
}) {
  const f = useFormat()
  const [text, setText] = useState('')
  const submit = () => {
    if (!text.trim() || busy || disabled) return
    onSend(text)
    setText('')
  }
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }
  return (
    <div className="border-t p-3">
      {notice ? <Alert variant="warning" className="mb-2">{notice}</Alert> : null}
      <div className="flex items-end gap-2">
        <Textarea value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} disabled={disabled} maxLength={MAX_MESSAGE}
          rows={1} placeholder="سؤال یا تراکنشتان را بنویسید…" aria-label="پیام"
          className="max-h-40 min-h-11 resize-none [field-sizing:content]" />
        {busy ? (
          <Button size="icon" variant="outline" className="size-11 shrink-0" aria-label="توقف پاسخ" onClick={onStop}><Square className="fill-current" /></Button>
        ) : (
          <Button size="icon" className="size-11 shrink-0" aria-label="ارسال" disabled={disabled || !text.trim()} onClick={submit}>
            <SendHorizontal className="-scale-x-100" />
          </Button>
        )}
      </div>
      {text.length > MAX_MESSAGE * 0.9 ? (
        <Badge variant="warning" className="mt-1">{f.number(text.length)} از {f.number(MAX_MESSAGE)} نویسه</Badge>
      ) : null}
    </div>
  )
}
