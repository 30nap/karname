# کارنامه

وب‌اپ فارسی مدیریت مالی و دارایی شخصی با دستیار هوش مصنوعی.

> این README در حال تکمیل است؛ نسخه‌ی کامل (راه‌اندازی، استقرار و پیکربندی AI) در فاز آخر نوشته می‌شود.

## ساختار

| پوشه | توضیح |
|---|---|
| `backend/` | Spring Boot 4.1 روی Java 25 (Maven) |
| `frontend/` | React 19 + TypeScript + Vite |
| `deploy/` | Docker Compose و nginx |
| `docs/` | مستندات معماری و راهنماها |

## اجرای محلی (توسعه)

پیش‌نیازها: JDK 25، Node.js 22 با pnpm 10، PostgreSQL 16 (یا Docker).

```bash
# دیتابیس
createuser -P karname   # رمز: karname
createdb -O karname karname

# بک‌اند (پورت 8080)
cd backend && ./mvnw spring-boot:run

# فرانت (پورت 5173، درخواست‌های /api به بک‌اند proxy می‌شوند)
cd frontend && pnpm install && pnpm dev
```
