-- Automatic price sources, run by the scheduler and managed by the admin. Fetched prices are
-- stored as instance-wide rows in prices (user_id NULL) with the source's name.
CREATE TABLE price_sources (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(40)  NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    url              VARCHAR(500),
    -- request headers as encrypted JSON (they may carry API keys)
    headers_enc      TEXT,
    -- unit of the numbers in the response
    unit             VARCHAR(8)   NOT NULL DEFAULT 'TOMAN',
    -- JSON array of {commodity, path, multiplier}: what to read for each commodity
    mappings         TEXT         NOT NULL DEFAULT '[]',
    interval_minutes INT          NOT NULL DEFAULT 30,
    enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_run_at      TIMESTAMPTZ,
    last_success_at  TIMESTAMPTZ,
    last_error       VARCHAR(500),
    last_count       INT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT price_sources_name_uk UNIQUE (name),
    CONSTRAINT price_sources_kind_ck CHECK (kind IN ('NOBITEX', 'JSON')),
    CONSTRAINT price_sources_unit_ck CHECK (unit IN ('RIAL', 'TOMAN')),
    CONSTRAINT price_sources_interval_ck CHECK (interval_minutes BETWEEN 5 AND 1440)
);

-- Nobitex publishes crypto prices in Rial without an API key; off until the admin turns it on,
-- since the server then calls an outside service on a schedule.
INSERT INTO price_sources (name, kind, url, unit, mappings, interval_minutes, enabled) VALUES
    ('نوبیتکس', 'NOBITEX', 'https://apiv2.nobitex.ir', 'RIAL',
     '[{"commodity":"USDT","path":"usdt"},{"commodity":"BTC","path":"btc"},{"commodity":"ETH","path":"eth"},{"commodity":"TON","path":"ton"}]',
     30, FALSE);

CREATE INDEX prices_auto_purge_ix ON prices (priced_at) WHERE user_id IS NULL;
