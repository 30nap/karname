-- Core finance model: commodities and prices, accounts, categories, transactions.
--
-- Every account holds exactly one commodity (Toman, a foreign currency, grams of gold, coins,
-- crypto, or a user-defined unit such as fund units or a property). Balances are never stored:
-- they are derived from transactions. Iranian money is stored in Toman (code IRT).

CREATE TABLE commodities (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    user_id    BIGINT REFERENCES users (id) ON DELETE CASCADE, -- NULL: built-in commodity
    name_fa    VARCHAR(100) NOT NULL,
    unit_fa    VARCHAR(30)  NOT NULL,
    kind       VARCHAR(16)  NOT NULL,
    scale      SMALLINT     NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT commodities_kind_ck CHECK (kind IN ('TOMAN', 'FIAT', 'GOLD', 'COIN', 'CRYPTO', 'SECURITY', 'PROPERTY', 'VEHICLE', 'OTHER')),
    CONSTRAINT commodities_scale_ck CHECK (scale BETWEEN 0 AND 8)
);

CREATE UNIQUE INDEX commodities_builtin_code_uk ON commodities (code) WHERE user_id IS NULL;
CREATE UNIQUE INDEX commodities_user_code_uk ON commodities (user_id, code) WHERE user_id IS NOT NULL;

INSERT INTO commodities (code, name_fa, unit_fa, kind, scale, sort_order) VALUES
    ('IRT',          'تومان',            'تومان',      'TOMAN',  0, 0),
    ('USD',          'دلار آمریکا',      'دلار',       'FIAT',   2, 10),
    ('EUR',          'یورو',             'یورو',       'FIAT',   2, 11),
    ('GBP',          'پوند انگلیس',      'پوند',       'FIAT',   2, 12),
    ('AED',          'درهم امارات',      'درهم',       'FIAT',   2, 13),
    ('TRY',          'لیر ترکیه',        'لیر',        'FIAT',   2, 14),
    ('CAD',          'دلار کانادا',      'دلار کانادا', 'FIAT',  2, 15),
    ('AUD',          'دلار استرالیا',    'دلار استرالیا', 'FIAT', 2, 16),
    ('CNY',          'یوان چین',         'یوان',       'FIAT',   2, 17),
    ('GOLD18',       'طلای ۱۸ عیار',     'گرم',        'GOLD',   3, 20),
    ('GOLD24',       'طلای ۲۴ عیار',     'گرم',        'GOLD',   3, 21),
    ('MESGHAL',      'طلای آب‌شده',      'مثقال',      'GOLD',   3, 22),
    ('COIN_EMAMI',   'سکه امامی',        'سکه',        'COIN',   0, 30),
    ('COIN_BAHAR',   'سکه بهار آزادی',   'سکه',        'COIN',   0, 31),
    ('COIN_HALF',    'نیم‌سکه',          'عدد',        'COIN',   0, 32),
    ('COIN_QUARTER', 'ربع‌سکه',          'عدد',        'COIN',   0, 33),
    ('COIN_GERAMI',  'سکه گرمی',         'عدد',        'COIN',   0, 34),
    ('USDT',         'تتر',              'تتر',        'CRYPTO', 2, 40),
    ('BTC',          'بیت‌کوین',         'بیت‌کوین',   'CRYPTO', 8, 41),
    ('ETH',          'اتریوم',           'اتر',        'CRYPTO', 6, 42),
    ('TON',          'تون‌کوین',         'تون',        'CRYPTO', 4, 43);

CREATE TABLE accounts (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                 VARCHAR(100) NOT NULL,
    type                 VARCHAR(20)  NOT NULL,
    commodity_id         BIGINT       NOT NULL REFERENCES commodities (id),
    bank                 VARCHAR(32),
    identifier_hints     VARCHAR(60), -- last digits of card/account numbers, for matching bank SMS
    counterparty         VARCHAR(100), -- person for debts/receivables
    icon                 VARCHAR(32),
    include_in_net_worth BOOLEAN      NOT NULL DEFAULT TRUE,
    archived             BOOLEAN      NOT NULL DEFAULT FALSE,
    notes                TEXT,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT accounts_type_ck CHECK (type IN ('CASH', 'BANK', 'EWALLET', 'CURRENCY', 'GOLD', 'CRYPTO', 'INVESTMENT',
                                                'PROPERTY', 'VEHICLE', 'RECEIVABLE', 'OTHER_ASSET', 'LOAN', 'DEBT', 'CREDIT'))
);

CREATE INDEX accounts_user_ix ON accounts (user_id);

CREATE TABLE categories (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id  BIGINT REFERENCES categories (id) ON DELETE CASCADE,
    kind       VARCHAR(8)  NOT NULL,
    name       VARCHAR(60) NOT NULL,
    icon       VARCHAR(32),
    system_key VARCHAR(40),
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT categories_kind_ck CHECK (kind IN ('INCOME', 'EXPENSE'))
);

CREATE INDEX categories_user_ix ON categories (user_id);
CREATE UNIQUE INDEX categories_name_uk ON categories (user_id, COALESCE(parent_id, 0), kind, name);
CREATE UNIQUE INDEX categories_system_key_uk ON categories (user_id, system_key) WHERE system_key IS NOT NULL;

CREATE TABLE transactions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                VARCHAR(12)    NOT NULL,
    occurred_on         DATE           NOT NULL,
    account_id          BIGINT         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    amount              NUMERIC(24, 8) NOT NULL, -- in the account's commodity
    to_account_id       BIGINT REFERENCES accounts (id) ON DELETE CASCADE,
    to_amount           NUMERIC(24, 8),          -- in the destination account's commodity
    fee                 NUMERIC(24, 8),          -- transfer fee, in the source account's commodity
    category_id         BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    description         VARCHAR(300),
    notes               TEXT,
    tags                VARCHAR(300),
    search_text         TEXT           NOT NULL DEFAULT '',
    source              VARCHAR(16)    NOT NULL DEFAULT 'MANUAL',
    external_ref        VARCHAR(100),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT tx_type_ck CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER', 'OPENING', 'ADJUSTMENT')),
    CONSTRAINT tx_amount_ck CHECK (type IN ('OPENING', 'ADJUSTMENT') OR amount > 0),
    CONSTRAINT tx_transfer_ck CHECK ((type = 'TRANSFER') = (to_account_id IS NOT NULL AND to_amount IS NOT NULL)),
    CONSTRAINT tx_to_amount_ck CHECK (to_amount IS NULL OR to_amount > 0),
    CONSTRAINT tx_fee_ck CHECK (fee IS NULL OR (fee >= 0 AND type = 'TRANSFER')),
    CONSTRAINT tx_distinct_accounts_ck CHECK (to_account_id IS NULL OR to_account_id <> account_id),
    CONSTRAINT tx_source_ck CHECK (source IN ('MANUAL', 'AI', 'SMS', 'RECURRING', 'IMPORT', 'LOAN', 'CHEQUE', 'SYSTEM'))
);

CREATE INDEX tx_user_date_ix ON transactions (user_id, occurred_on DESC, id DESC);
CREATE INDEX tx_account_ix ON transactions (account_id, occurred_on);
CREATE INDEX tx_to_account_ix ON transactions (to_account_id, occurred_on) WHERE to_account_id IS NOT NULL;
CREATE INDEX tx_category_ix ON transactions (category_id);
CREATE UNIQUE INDEX tx_external_ref_uk ON transactions (user_id, external_ref) WHERE external_ref IS NOT NULL;

-- Price of one unit of a commodity in Toman. user_id NULL = instance-wide (automatic sources
-- or the admin); otherwise a user's own price. The most recent price at or before a moment wins.
CREATE TABLE prices (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commodity_id BIGINT        NOT NULL REFERENCES commodities (id) ON DELETE CASCADE,
    user_id      BIGINT REFERENCES users (id) ON DELETE CASCADE,
    price_toman  NUMERIC(24, 8) NOT NULL,
    priced_at    TIMESTAMPTZ   NOT NULL,
    source       VARCHAR(40)   NOT NULL,
    -- set when the price was implied by an exchange (e.g. buying a coin with Toman)
    transaction_id BIGINT REFERENCES transactions (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT prices_positive_ck CHECK (price_toman > 0)
);

CREATE INDEX prices_commodity_time_ix ON prices (commodity_id, priced_at DESC);
CREATE INDEX prices_user_ix ON prices (user_id, commodity_id, priced_at DESC);

-- Signed effect of each transaction on each account it touches.
CREATE VIEW ledger_postings AS
SELECT t.id AS transaction_id, t.user_id, t.occurred_on, t.account_id,
       CASE t.type
           WHEN 'INCOME' THEN t.amount
           WHEN 'EXPENSE' THEN -t.amount
           WHEN 'TRANSFER' THEN -(t.amount + COALESCE(t.fee, 0))
           ELSE t.amount -- OPENING / ADJUSTMENT are signed
       END AS delta
FROM transactions t
UNION ALL
SELECT t.id, t.user_id, t.occurred_on, t.to_account_id, t.to_amount
FROM transactions t
WHERE t.type = 'TRANSFER';

-- Learned description -> category mappings ("Snapp" -> Taxi), so categorization repeats
-- without asking the AI again.
CREATE TABLE merchant_rules (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pattern     VARCHAR(120) NOT NULL, -- normalized description
    category_id BIGINT       NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    hits        INT          NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT merchant_rules_uk UNIQUE (user_id, pattern)
);
