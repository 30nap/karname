-- Planning: monthly budgets per expense category, and savings goals.

-- A budget amount for a category takes effect in a Jalali month ('YYYY-MM'). A recurring row also
-- applies to the following months, until the next recurring row of the same category; a
-- non-recurring row applies to its own month only. An amount of zero means "no budget".
CREATE TABLE budgets (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category_id  BIGINT         NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    month        VARCHAR(7)     NOT NULL,
    amount_toman NUMERIC(24, 8) NOT NULL,
    recurring    BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version      BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT budgets_amount_ck CHECK (amount_toman >= 0),
    CONSTRAINT budgets_month_ck CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT budgets_uk UNIQUE (user_id, category_id, month)
);

CREATE INDEX budgets_user_month_ix ON budgets (user_id, month);

-- A savings goal in any commodity ("15,000 euros for emigrating"). Progress is the value of the
-- linked asset accounts, or a manually entered amount when no account is linked.
CREATE TABLE goals (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(100)   NOT NULL,
    icon          VARCHAR(32),
    target_amount NUMERIC(24, 8) NOT NULL,
    commodity_id  BIGINT         NOT NULL REFERENCES commodities (id),
    target_date   DATE,
    manual_amount NUMERIC(24, 8),
    archived      BOOLEAN        NOT NULL DEFAULT FALSE,
    achieved_at   TIMESTAMPTZ,
    notes         TEXT,
    sort_order    INT            NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT goals_target_ck CHECK (target_amount > 0),
    CONSTRAINT goals_manual_ck CHECK (manual_amount IS NULL OR manual_amount >= 0)
);

CREATE INDEX goals_user_ix ON goals (user_id);

CREATE TABLE goal_accounts (
    goal_id    BIGINT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    PRIMARY KEY (goal_id, account_id)
);

CREATE INDEX goal_accounts_account_ix ON goal_accounts (account_id);
