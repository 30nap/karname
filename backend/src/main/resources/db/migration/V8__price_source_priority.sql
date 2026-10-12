-- Several automatic sources per commodity: each source keeps its latest quote per commodity, and
-- the recorded price comes from the most preferred source whose quote is fresh (lower priority
-- number first); the others are fallbacks and cross-checks.
ALTER TABLE price_sources ADD COLUMN priority INT NOT NULL DEFAULT 100;
-- the built-in template a source was created from (null for hand-made ones)
ALTER TABLE price_sources ADD COLUMN preset VARCHAR(24);
-- an API key sent in place of {key} in the address or a header value, encrypted like the headers
ALTER TABLE price_sources ADD COLUMN api_key_enc TEXT;

UPDATE price_sources s SET priority = o.rn * 10
FROM (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM price_sources) o
WHERE s.id = o.id;
UPDATE price_sources SET preset = 'NOBITEX' WHERE kind = 'NOBITEX';

CREATE TABLE price_quotes (
    source_id    BIGINT        NOT NULL REFERENCES price_sources (id) ON DELETE CASCADE,
    commodity_id BIGINT        NOT NULL REFERENCES commodities (id) ON DELETE CASCADE,
    price_toman  NUMERIC(24, 8) NOT NULL,
    fetched_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (source_id, commodity_id),
    CONSTRAINT price_quotes_positive_ck CHECK (price_toman > 0)
);
CREATE INDEX price_quotes_commodity_ix ON price_quotes (commodity_id);

-- Toncoin was renamed Gram in June 2026 and Nobitex closed its TON markets: asking for "ton" now
-- makes Nobitex refuse the whole request (HTTP 400), so the stored symbol follows the rename.
UPDATE price_sources SET mappings = replace(mappings, '"path":"ton"', '"path":"gram"') WHERE kind = 'NOBITEX';
