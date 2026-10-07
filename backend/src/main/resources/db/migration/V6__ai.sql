-- AI assistant: providers chosen by the administrator, which provider/model serves each task,
-- conversations, usage accounting and cached monthly reports.

CREATE TABLE ai_providers (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(80)  NOT NULL,
    kind                 VARCHAR(24)  NOT NULL,
    preset               VARCHAR(24)  NOT NULL,
    base_url             VARCHAR(500) NOT NULL,
    api_key_enc          TEXT,                         -- AES-GCM, never returned to clients
    key_from_env         BOOLEAN      NOT NULL DEFAULT false,
    headers_enc          TEXT,                         -- encrypted JSON object: extra request headers
    query_params         TEXT,                         -- JSON object, e.g. {"api-version": "..."}
    default_model        VARCHAR(200),
    supports_tools       BOOLEAN      NOT NULL DEFAULT true,
    supports_json_schema BOOLEAN      NOT NULL DEFAULT false,
    stream_usage         BOOLEAN      NOT NULL DEFAULT true,
    refusal_fallback     BOOLEAN      NOT NULL DEFAULT true,
    enabled              BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ai_providers_name_uq UNIQUE (name),
    CONSTRAINT ai_providers_kind_ck CHECK (kind IN ('ANTHROPIC', 'OPENAI_COMPATIBLE', 'FAKE'))
);

-- Which provider and model serve each task: chat, extraction (quick add, SMS, categorizing), reports.
CREATE TABLE ai_routes (
    task        VARCHAR(16)  PRIMARY KEY,
    provider_id BIGINT       NOT NULL REFERENCES ai_providers (id) ON DELETE CASCADE,
    model       VARCHAR(200) NOT NULL,
    effort      VARCHAR(8),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ai_routes_task_ck CHECK (task IN ('CHAT', 'EXTRACT', 'REPORT')),
    CONSTRAINT ai_routes_effort_ck CHECK (effort IS NULL OR effort IN ('LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'))
);

-- A conversation stays with the provider and model it started on: replies are replayed to the
-- same model exactly as received.
CREATE TABLE ai_conversations (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(120) NOT NULL,
    provider_id   BIGINT       REFERENCES ai_providers (id) ON DELETE SET NULL,
    provider_kind VARCHAR(24)  NOT NULL,
    model         VARCHAR(200) NOT NULL,
    turns         INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX ai_conversations_user_ix ON ai_conversations (user_id, updated_at DESC);

-- Append-only: messages are never edited. A failed or stopped turn is kept for display but
-- excluded from what is sent to the model.
CREATE TABLE ai_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    seq             INT         NOT NULL,
    turn            INT         NOT NULL,
    role            VARCHAR(16) NOT NULL,
    parts           TEXT        NOT NULL,              -- provider-neutral parts (JSON)
    native_format   VARCHAR(16),
    native_json     TEXT,                              -- the provider's own form, replayed verbatim
    excluded        BOOLEAN     NOT NULL DEFAULT false,
    error           TEXT,                              -- why the turn failed (on its first message)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ai_messages_seq_uq UNIQUE (conversation_id, seq),
    CONSTRAINT ai_messages_role_ck CHECK (role IN ('USER', 'ASSISTANT'))
);

-- One row per AI operation (a chat turn, an extraction, a report…), summed over its model calls.
CREATE TABLE ai_usage (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT        REFERENCES users (id) ON DELETE CASCADE,
    task               VARCHAR(16)   NOT NULL,
    provider_id        BIGINT        REFERENCES ai_providers (id) ON DELETE SET NULL,
    provider_name      VARCHAR(80),
    model              VARCHAR(200),
    calls              INT           NOT NULL DEFAULT 0,
    input_tokens       BIGINT        NOT NULL DEFAULT 0,
    output_tokens      BIGINT        NOT NULL DEFAULT 0,
    cache_read_tokens  BIGINT        NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(14, 6),
    outcome            VARCHAR(16)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ai_usage_outcome_ck CHECK (outcome IN ('OK', 'ERROR', 'REFUSED', 'STOPPED'))
);

CREATE INDEX ai_usage_user_ix ON ai_usage (user_id, created_at);
CREATE INDEX ai_usage_created_ix ON ai_usage (created_at);

-- The AI's narrative for a Jalali month, with a hash of the figures it was written from so the
-- report can tell when the data has changed since.
CREATE TABLE ai_reports (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    month      CHAR(7)      NOT NULL,
    content    TEXT         NOT NULL,
    data_hash  CHAR(64)     NOT NULL,
    model      VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ai_reports_month_uq UNIQUE (user_id, month)
);
