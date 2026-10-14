-- A personal security can follow an Iranian exchange instrument. Existing manual assets stay unchanged.
ALTER TABLE commodities
    ADD COLUMN stock_instrument_id VARCHAR(20),
    ADD COLUMN stock_symbol VARCHAR(50),
    ADD COLUMN stock_last_attempt_at TIMESTAMPTZ,
    ADD COLUMN stock_last_success_at TIMESTAMPTZ,
    ADD COLUMN stock_last_error VARCHAR(500),
    ADD CONSTRAINT commodities_stock_link_ck CHECK (
        (stock_instrument_id IS NULL AND stock_symbol IS NULL)
        OR (stock_instrument_id IS NOT NULL AND stock_instrument_id ~ '^[1-9][0-9]{0,19}$'
            AND stock_symbol IS NOT NULL AND length(trim(stock_symbol)) > 0
            AND user_id IS NOT NULL AND kind = 'SECURITY' AND scale = 0)
    );

CREATE UNIQUE INDEX commodities_user_stock_uk ON commodities (user_id, stock_instrument_id)
    WHERE stock_instrument_id IS NOT NULL;
