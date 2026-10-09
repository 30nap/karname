# Deployment

Karname runs as three containers: PostgreSQL, the Spring Boot backend, and nginx, which serves the
app and forwards `/api` to the backend, so the browser sees a single origin. Everything lives in
`deploy/`.

## First start

```bash
cd deploy
cp .env.example .env
```

Fill in at least:

- `POSTGRES_PASSWORD`: any long random string (`openssl rand -base64 24`).
- `KARNAME_SECRET_KEY`: `openssl rand -base64 32`. It encrypts the API keys stored in the
  database. **Keep it with your backups**: a restored database cannot decrypt stored keys
  without the same value (everything else still works; you would re-enter the keys).

```bash
docker compose up -d --build
docker compose ps          # db, backend and web should become "healthy"
```

The app listens on `127.0.0.1:8080` by default (`KARNAME_BIND`, `KARNAME_HTTP_PORT`). The first
account created becomes the administrator; open registration can then be closed in
**تنظیمات → مدیریت**.

Resources: the backend uses up to 75% of the container's memory limit; 1 GB of RAM for the whole
stack is comfortable for a household. Disk usage is small (prices fetched every 30 minutes are
thinned to one per day after a while).

## HTTPS

Put a TLS-terminating proxy in front and keep Karname bound to `127.0.0.1`. With
[Caddy](https://caddyserver.com) on the same host:

```
karname.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Then set in `.env`:

```
KARNAME_COOKIE_SECURE=true
KARNAME_HSTS=max-age=31536000
# the proxy reaches nginx through Docker's network; trust its X-Forwarded-For
KARNAME_REAL_IP_FROM=172.16.0.0/12
```

`KARNAME_REAL_IP_FROM` matters: without it, nginx sees every visitor as the proxy's address, and
sign-in and registration limits would apply to everyone at once. List only the proxy's addresses
(CIDR, space separated); a client cannot spoof its address otherwise. The assistant's answers are
streamed (Server-Sent Events): Caddy passes them through as is; for other proxies, disable response
buffering for `/api/v1/ai/chat` and allow responses of up to five minutes.

## Backups

```bash
./backup.sh                  # writes backups/karname-YYYYmmdd-HHMMSS.sql.gz, keeps the newest 14 (KEEP=n)
./restore.sh backups/karname-20261007-114156.sql.gz   # replaces everything; asks for confirmation
```

Schedule `backup.sh` with cron, copy `backups/` and `.env` off the machine, and test a restore
from time to time. Each user can also download their own data as JSON in **ورود و خروج داده** and
restore it (into the same or another account).

## Updating

```bash
git pull
cd deploy && docker compose up -d --build
```

Database migrations run automatically on start. Take a backup first.

## Local AI with Ollama

```bash
docker compose --profile ollama up -d
docker compose exec ollama ollama pull qwen2.5:7b
```

In **تنظیمات → هوش مصنوعی**, add the "Ollama" preset with the address `http://ollama:11434/v1` and
the model name. Nothing leaves the server. Small models handle quick add and SMS reasonably;
analysis is noticeably better with large hosted models (see [ai-providers.md](ai-providers.md)).

## Demo instance

```
KARNAME_DEMO=true
KARNAME_DEMO_PASSWORD=choose-one
KARNAME_AI_FAKE=true
```

On first start a `demo` user gets six months of sample data, dated relative to today, and the
offline scripted model answers every AI task. Never enable `KARNAME_AI_FAKE` on a real instance.

## Behind the scenes

- **Images**: the backend image is built with Gradle on JDK 25 and runs on a JRE as an
  unprivileged user; the web image is the production build on nginx.
- **nginx** sets a strict Content-Security-Policy (scripts only from the same origin) and the
  other security headers, caches hashed assets for a year and always revalidates the app shell,
  limits requests to the password endpoints, never buffers the assistant's stream, and replaces
  any client-supplied forwarding headers with the real client address.
- **Health**: the backend reports at `/actuator/health/liveness`; Compose waits for it before
  starting nginx.
