import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { usePrefs } from '@/app/preferences'
import { cn } from '@/lib/cn'
import { toPersianNumerals } from '@/lib/persian/digits'

interface MdNode {
  type: string
  value?: string
  url?: string
  children?: MdNode[]
}

/** Shows the numbers of the model's prose in Persian form (code and bare links are left alone). */
function persianDigits() {
  return (tree: MdNode) => {
    const walk = (node: MdNode) => {
      if (node.type === 'link' && node.children?.length === 1 && node.children[0].value === node.url) return
      if (node.type === 'text' && node.value) node.value = toPersianNumerals(node.value)
      node.children?.forEach(walk)
    }
    walk(tree)
  }
}

const components: Components = {
  p: ({ children }) => <p className="leading-7 [&:not(:last-child)]:mb-3">{children}</p>,
  ul: ({ children }) => <ul className="mb-3 list-disc space-y-1 ps-5 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="mb-3 list-decimal space-y-1 ps-5 last:mb-0">{children}</ol>,
  li: ({ children }) => <li className="leading-7">{children}</li>,
  strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
  h1: ({ children }) => <h3 className="mb-2 mt-3 text-base font-semibold first:mt-0">{children}</h3>,
  h2: ({ children }) => <h3 className="mb-2 mt-3 text-base font-semibold first:mt-0">{children}</h3>,
  h3: ({ children }) => <h4 className="mb-2 mt-3 font-semibold first:mt-0">{children}</h4>,
  a: ({ children, href }) => (
    <a href={href} target="_blank" rel="noreferrer noopener" className="font-medium text-primary underline underline-offset-4">{children}</a>
  ),
  code: ({ children }) => <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em]" dir="ltr">{children}</code>,
  blockquote: ({ children }) => <blockquote className="mb-3 border-s-2 ps-3 text-muted-foreground">{children}</blockquote>,
  hr: () => <hr className="my-3 border-border" />,
  table: ({ children }) => (
    <div className="mb-3 max-w-full overflow-x-auto rounded-lg border last:mb-0">
      <table className="w-full text-sm">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-muted/60 text-xs text-muted-foreground">{children}</thead>,
  th: ({ children }) => <th className="whitespace-nowrap px-3 py-2 text-start font-medium">{children}</th>,
  td: ({ children }) => <td className="border-t px-3 py-2">{children}</td>,
}

/** The assistant's Markdown: no raw HTML, safe links, digits in the user's style. */
export function AiMarkdown({ children, className }: { children: string; className?: string }) {
  const prefs = usePrefs()
  const persian = prefs.digits === 'PERSIAN'
  return (
    <div className={cn('min-w-0 break-words text-sm', persian && '[&_ol]:[list-style-type:persian]', className)}>
      <ReactMarkdown remarkPlugins={persian ? [remarkGfm, persianDigits] : [remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
