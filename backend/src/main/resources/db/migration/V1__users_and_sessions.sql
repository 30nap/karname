-- Users, per-user settings, instance settings and Spring Session tables.

CREATE TABLE users (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username            VARCHAR(32)  NOT NULL,
    display_name        VARCHAR(100) NOT NULL,
    password_hash       VARCHAR(200) NOT NULL,
    role                VARCHAR(16)  NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    totp_secret         TEXT,
    totp_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_recovery_codes TEXT,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT users_username_uk UNIQUE (username),
    CONSTRAINT users_role_ck CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE user_settings (
    user_id               BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    display_unit          VARCHAR(8)   NOT NULL DEFAULT 'TOMAN',
    digit_style           VARCHAR(8)   NOT NULL DEFAULT 'PERSIAN',
    theme                 VARCHAR(8)   NOT NULL DEFAULT 'SYSTEM',
    wealth_units          VARCHAR(200) NOT NULL DEFAULT 'USD,GOLD18',
    inflation_rate        NUMERIC(6, 2),
    ai_enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    ai_share_descriptions BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE app_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Spring Session JDBC (schema-postgresql.sql from spring-session-jdbc 4.1)
CREATE TABLE spring_session (
    primary_id            CHAR(36)     NOT NULL,
    session_id            CHAR(36)     NOT NULL,
    creation_time         BIGINT       NOT NULL,
    last_access_time      BIGINT       NOT NULL,
    max_inactive_interval INT          NOT NULL,
    expiry_time           BIGINT       NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
