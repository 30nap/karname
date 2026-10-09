# AI services

Karname works completely without AI. With a service configured, it adds:

| Feature | Where | Task (routing) |
|---|---|---|
| Assistant chat over your own figures | دستیار | Chat |
| Quick add from a sentence ("دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن") | Transaction dialog → «یا با یک جمله بنویسید» | Reading text and SMS |
| Bank SMS import | ورود پیامک بانکی | Reading text and SMS |
| Smart categorization of uncategorised transactions | تراکنش‌ها → دسته‌بندی هوشمند | Reading text and SMS |
| Monthly report with suggestions | گزارش‌ها → گزارش هوشمند | Monthly report |

## How it stays trustworthy

- **The model never does the arithmetic.** Balances, totals, budgets, projections and conversions
  come from the app's own calculations through tools (and an exact decimal calculator for ad-hoc
  sums); the model reads them and explains.
- **Nothing is written without you.** Quick add, SMS import and the chat's "record this" all
  produce drafts; you check, edit and confirm them. Each draft has a reference, so recording it
  twice does nothing.
- **Amounts are converted in code.** Rial in SMS becomes Toman, and "۱۸۰ تومن" for lunch becomes
  180,000 while "۸۰۰ تومن" for a car becomes 800 million: the model states the scale with a
  confidence, and doubtful drafts are flagged.
- **Your data is treated as data.** SMS text and descriptions are wrapped as quoted data, not
  instructions, and the tools only ever see the signed-in user's records.

## Setting up a service

As the administrator, open **تنظیمات → هوش مصنوعی**, choose **سرویس جدید**, pick a preset, enter the
key, press **دریافت فهرست مدل‌ها** and **آزمایش اتصال** (which also checks that the model can call
tools), and save. Then choose a service, a model and (for Claude) an effort for each task under
**مدل هر کار**.

| Preset | Address | Notes |
|---|---|---|
| Anthropic (Claude) | `https://api.anthropic.com` | Default model `claude-opus-5-5`. Tools, structured output, streaming. |
| OpenAI | `https://api.openai.com/v1` | |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | OpenAI-compatible endpoint. |
| DeepSeek | `https://api.deepseek.com/v1` | JSON asked for in the prompt. |
| OpenRouter | `https://openrouter.ai/api/v1` | Many models behind one key. |
| Groq | `https://api.groq.com/openai/v1` | JSON asked for in the prompt. |
| Mistral | `https://api.mistral.ai/v1` | No usage figures in the stream. |
| xAI (Grok) | `https://api.x.ai/v1` | |
| Ollama | `http://localhost:11434/v1` (`http://ollama:11434/v1` in Docker) | No key; tools off by default. |
| LM Studio | `http://localhost:1234/v1` | No key. |
| Other OpenAI-compatible | any | Gateways, vLLM, Azure OpenAI (add the `api-key` header and the `api-version` parameter under advanced settings). |

**Claude from the environment:** with `ANTHROPIC_API_KEY` set, a Claude service is created on the
first start and every task is routed to it. The key is read from the environment each time and
never stored; it is only ever sent to `api.anthropic.com`.

**Keys** are encrypted in the database (AES-GCM with `KARNAME_SECRET_KEY`) and never sent back to
the browser. A stored key is only sent to the address it was saved for: changing a service's
address requires entering the key again. Requests do not follow redirects, and link-local
addresses (such as cloud metadata services) are refused; addresses on the server itself or the
local network are allowed.

### Options per service

- **Tool calling**: off for models that cannot use tools. Karname then attaches a summary of your
  figures (overview, accounts, the last six months, this month's spending by category, budgets,
  goals and the next 30 days) to each question. Answers are more limited but still grounded in
  real numbers.
- **JSON Schema**: when off, the required JSON shape is described in the prompt, the reply is read
  leniently, and one retry explains what was wrong.
- **Usage in the stream**: turn off if the service rejects `stream_options`.
- **Fallback model** (Claude): if the requested model declines for safety reasons, Anthropic's
  recommended model answers instead.

### Effort (Claude)

Chat uses medium effort, reading text uses low, and the monthly report uses high by default.
Higher effort means more careful answers, more slowly and at a higher cost.

## Privacy

What is sent to the service: your question; the figures the tools return (amounts, dates,
category and account names); and, unless you turn it off in your settings, transaction
descriptions. Card numbers, IBANs (Sheba), mobile and national ID numbers are masked before
anything leaves the server, and SMS keep only the last four digits of cards and accounts.
Conversations are stored in your database so you can return to them; deleting one removes it.

Each user can switch the assistant off, or stop descriptions being shared, in
**تنظیمات → عمومی**. For complete privacy, use a local model.

## Access from Iran

Anthropic and OpenAI do not serve users in Iran. The options are:

1. Run Karname on a server outside Iran.
2. Use an OpenAI-compatible gateway that is reachable from Iran (the "Other" preset).
3. Run a local model with Ollama (see [deployment.md](deployment.md)). Small local models are
   weaker in Persian and at tool use; quick add and SMS work reasonably, analysis noticeably less
   well than with large hosted models.

## Limits and costs

- A daily limit of AI requests per user (default 100, `KARNAME_AI_DAILY_LIMIT`, then
  **سقف استفاده**), counting requests still in progress. One chat message is one request,
  even when the assistant uses several tools.
- Chat: up to 4,000 characters per message, 40 turns per conversation, ten tool rounds and four
  minutes per answer. One answer at a time per user.
- **مصرف ۳۰ روز اخیر** lists requests, tokens and, for Claude models, an estimated cost in dollars.

## Testing

- Offline: `KARNAME_AI_FAKE=true` adds "مدل آزمایشی آفلاین", a scripted model that answers every
  task deterministically. The automated tests and the demo use it. Never enable it for real use.
- Live: `cd backend && ANTHROPIC_API_KEY=... ./gradlew liveAiTest` runs Persian quick-add and SMS
  examples (scales, mixed digits, several transactions in one message, relative dates) against
  Claude, and fails if too few are read correctly. Without a key, these tests are skipped.
