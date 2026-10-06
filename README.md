# Karname

**Karname** (Persian: «کارنامه», "record") is a self-hosted personal finance and asset
management web app for Iranian users, with an AI assistant that analyses and explains
your finances. The user interface is fully Persian (RTL, Solar Hijri calendar, Persian
digits, Toman amounts).

> This README is a work in progress; the full version (setup, deployment and AI
> configuration) is written in the final phase.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot 4.1 on Java 25 (Gradle, Kotlin DSL) |
| `frontend/` | React 19 + TypeScript + Vite |
| `deploy/` | Docker Compose and nginx configuration |
| `docs/` | Architecture notes and guides |

## Local development

Requirements: JDK 25, Node.js 22 with pnpm 10, PostgreSQL 16 (or Docker).

```bash
# Database
createuser -P karname   # password: karname
createdb -O karname karname

# Backend (port 8080)
cd backend && ./gradlew bootRun

# Frontend (port 5173; /api requests are proxied to the backend)
cd frontend && pnpm install && pnpm dev
```
