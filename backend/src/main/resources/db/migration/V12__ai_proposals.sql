-- What the assistant proposed to record (a loan, an account, a budget…) and what the user did with it.
-- The assistant only proposes: the user's click applies the stored payload through the same services
-- as the app's own forms. Cards in a conversation refer to these rows by ref.
CREATE TABLE ai_proposals (
    id              BIGSERIAL PRIMARY KEY,
    ref             VARCHAR(40)  NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id BIGINT       REFERENCES ai_conversations (id) ON DELETE CASCADE,
    turn            INT          NOT NULL DEFAULT 0,
    kind            VARCHAR(24)  NOT NULL,
    -- the service request as proposed and shown (JSON), applied unchanged
    payload         TEXT         NOT NULL,
    -- the card as shown: title, lines, effects and warnings (JSON)
    card            TEXT         NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    link            VARCHAR(200),
    -- whether the assistant has been told what the user did with it
    reported        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    decided_at      TIMESTAMPTZ,
    CONSTRAINT ai_proposals_status_ck CHECK (status IN ('PENDING', 'APPLIED', 'DISMISSED', 'REPLACED'))
);

CREATE INDEX ai_proposals_conversation_ix ON ai_proposals (conversation_id, turn);
CREATE INDEX ai_proposals_user_ix ON ai_proposals (user_id);

-- The tools and instructions a conversation was started with: a conversation keeps them for its
-- whole life, since providers cache the prompt by its bytes and bind earlier reasoning to it.
ALTER TABLE ai_conversations ADD COLUMN tools_version SMALLINT NOT NULL DEFAULT 1;
