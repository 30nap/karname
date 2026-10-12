-- A quote counts until fresh_until: when it was fetched plus twice the source's interval at that
-- time and five minutes. Computed from the interval of the moment, so lengthening an interval
-- later does not bring an old quote back into use.
ALTER TABLE price_quotes ADD COLUMN fresh_until TIMESTAMPTZ;
UPDATE price_quotes q SET fresh_until = q.fetched_at + make_interval(mins => s.interval_minutes * 2 + 5)
FROM price_sources s WHERE s.id = q.source_id;
ALTER TABLE price_quotes ALTER COLUMN fresh_until SET NOT NULL;
