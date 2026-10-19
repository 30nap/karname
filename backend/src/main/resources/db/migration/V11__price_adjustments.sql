-- A security's price the exchange itself moved overnight (a capital increase, a cash dividend, a
-- merger of shares): its previous price for the new session differs from the last close recorded.
-- Its holder is told, as bonus shares may need recording; one row per security and session.
CREATE TABLE price_adjustments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commodity_id   BIGINT         NOT NULL REFERENCES commodities (id) ON DELETE CASCADE,
    session_day    DATE           NOT NULL,
    previous_toman NUMERIC(24, 8) NOT NULL,
    adjusted_toman NUMERIC(24, 8) NOT NULL,
    noticed_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT price_adjustments_session_uk UNIQUE (commodity_id, session_day)
);
