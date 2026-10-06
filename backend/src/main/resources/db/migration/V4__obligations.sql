-- Obligations: loans with installment schedules, recurring transactions, cheques, and in-app
-- notifications. Payments and postings are ordinary transactions linked through their
-- external_ref ("loan:{loan}:{number}:p", "rec:{rule}:{date}", "cheque:{id}"), so the ledger stays
-- the single source of truth and the unique (user_id, external_ref) index makes them idempotent.

-- Terms of a loan; the LOAN account holds the outstanding principal.
CREATE TABLE loans (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id         BIGINT         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    principal          NUMERIC(24, 8) NOT NULL,
    annual_rate        NUMERIC(7, 4)  NOT NULL,
    term_months        INT            NOT NULL,
    first_due_date     DATE           NOT NULL,
    method             VARCHAR(16)    NOT NULL,
    installment_amount NUMERIC(24, 8),            -- the bank's exact installment, overrides the formula
    paid_before        INT            NOT NULL DEFAULT 0, -- installments paid before the loan was entered
    payment_account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL,
    notes              TEXT,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT loans_account_uk UNIQUE (account_id),
    CONSTRAINT loans_principal_ck CHECK (principal > 0),
    CONSTRAINT loans_rate_ck CHECK (annual_rate >= 0 AND annual_rate <= 100),
    CONSTRAINT loans_term_ck CHECK (term_months BETWEEN 1 AND 600),
    CONSTRAINT loans_method_ck CHECK (method IN ('ANNUITY', 'EQUAL_PRINCIPAL')),
    CONSTRAINT loans_installment_ck CHECK (installment_amount IS NULL OR installment_amount > 0),
    CONSTRAINT loans_paid_before_ck CHECK (paid_before >= 0 AND paid_before <= term_months)
);

CREATE INDEX loans_user_ix ON loans (user_id);

CREATE TABLE loan_installments (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loan_id   BIGINT         NOT NULL REFERENCES loans (id) ON DELETE CASCADE,
    number    INT            NOT NULL,
    due_date  DATE           NOT NULL,
    amount    NUMERIC(24, 8) NOT NULL,
    principal NUMERIC(24, 8) NOT NULL,
    interest  NUMERIC(24, 8) NOT NULL,
    CONSTRAINT loan_installments_uk UNIQUE (loan_id, number)
);

CREATE INDEX loan_installments_due_ix ON loan_installments (due_date);

-- A transaction that repeats on a Jalali calendar rule. AUTO rules are posted by the scheduler
-- (catching up after downtime); REMIND rules only remind and wait for the user.
CREATE TABLE recurring_rules (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(100)   NOT NULL,
    type          VARCHAR(12)    NOT NULL,
    account_id    BIGINT         NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    to_account_id BIGINT REFERENCES accounts (id) ON DELETE CASCADE,
    amount        NUMERIC(24, 8) NOT NULL,
    to_amount     NUMERIC(24, 8),
    category_id   BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    description   VARCHAR(300),
    frequency     VARCHAR(8)     NOT NULL,
    interval_n    INT            NOT NULL DEFAULT 1,
    day_of_month  INT,            -- Jalali day 1..31, clamped to shorter months
    day_of_week   INT,            -- 0 = Saturday .. 6 = Friday
    month_of_year INT,            -- Jalali month for yearly rules
    start_date    DATE           NOT NULL,
    end_date      DATE,
    mode          VARCHAR(8)     NOT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT recurring_type_ck CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    CONSTRAINT recurring_amount_ck CHECK (amount > 0),
    CONSTRAINT recurring_frequency_ck CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT recurring_interval_ck CHECK (interval_n BETWEEN 1 AND 12),
    CONSTRAINT recurring_dom_ck CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 31),
    CONSTRAINT recurring_dow_ck CHECK (day_of_week IS NULL OR day_of_week BETWEEN 0 AND 6),
    CONSTRAINT recurring_moy_ck CHECK (month_of_year IS NULL OR month_of_year BETWEEN 1 AND 12),
    CONSTRAINT recurring_mode_ck CHECK (mode IN ('AUTO', 'REMIND'))
);

CREATE INDEX recurring_rules_user_ix ON recurring_rules (user_id);

-- Occurrences the user skipped (posted ones are transactions with external_ref "rec:{rule}:{date}").
CREATE TABLE recurring_skips (
    rule_id  BIGINT NOT NULL REFERENCES recurring_rules (id) ON DELETE CASCADE,
    due_date DATE   NOT NULL,
    PRIMARY KEY (rule_id, due_date)
);

CREATE TABLE cheques (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    direction         VARCHAR(8)     NOT NULL,
    status            VARCHAR(10)    NOT NULL DEFAULT 'PENDING',
    sayad_id          VARCHAR(16),
    serial            VARCHAR(30),
    bank              VARCHAR(32),
    account_id        BIGINT REFERENCES accounts (id) ON DELETE SET NULL, -- drawn on (issued) / deposited to (received)
    counter_account_id BIGINT REFERENCES accounts (id) ON DELETE SET NULL, -- debt or receivable it settles
    category_id       BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    counterparty      VARCHAR(100),
    amount            NUMERIC(24, 8) NOT NULL,
    issue_date        DATE,
    due_date          DATE           NOT NULL,
    settled_on        DATE,
    description       VARCHAR(300),
    notes             TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT cheques_direction_ck CHECK (direction IN ('ISSUED', 'RECEIVED')),
    CONSTRAINT cheques_status_ck CHECK (status IN ('PENDING', 'CLEARED', 'BOUNCED', 'CANCELLED')),
    CONSTRAINT cheques_amount_ck CHECK (amount > 0),
    CONSTRAINT cheques_sayad_ck CHECK (sayad_id IS NULL OR sayad_id ~ '^[0-9]{16}$')
);

CREATE INDEX cheques_user_due_ix ON cheques (user_id, due_date);

CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(32)  NOT NULL,
    severity   VARCHAR(8)   NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(500),
    link       VARCHAR(200),
    dedupe_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    CONSTRAINT notifications_severity_ck CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT notifications_dedupe_uk UNIQUE (user_id, dedupe_key)
);

CREATE INDEX notifications_user_ix ON notifications (user_id, created_at DESC);
