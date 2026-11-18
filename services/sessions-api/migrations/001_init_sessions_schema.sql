-- =============================================================================
-- Kanjora Sessions API - PostgreSQL Schema 001 (B11 - B14)
-- Owner: kanjora_sessions_app (schema: sessions via role search_path)
-- Rules enforced at DB level:
--   1. Only one active session per accepted proposal (partial unique index).
--   2. Strict session state machine CHECK constraint.
--   3. Exactly two teaching blocks per session (block_order IN (1, 2)).
--   4. Explicit session agreement tracking per participant.
--   5. Outbox table for reliable idempotent HTTP fact delivery Go -> Java.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sessions (
    session_id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id                  UUID         NOT NULL,
    participant_a_user_id        VARCHAR(128) NOT NULL,
    participant_b_user_id        VARCHAR(128) NOT NULL,
    status                       VARCHAR(40)  NOT NULL CHECK (
        status IN (
            'AWAITING_PARTNER_AGREEMENT',
            'SCHEDULED',
            'RESCHEDULE_PENDING_ACCEPTANCE',
            'IN_PROGRESS',
            'COMPLETED_OBSERVED',
            'CANCELLED',
            'NO_SHOW_OBSERVED',
            'TECHNICAL_FAILURE_OBSERVED',
            'ABANDONED_MID_CALL'
        )
    ),
    scheduled_start_at           TIMESTAMPTZ  NOT NULL,
    scheduled_end_at             TIMESTAMPTZ  NOT NULL,
    livekit_room_name            VARCHAR(160) NOT NULL UNIQUE,
    recommended_guide_ids_json   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    cancellation_reason          VARCHAR(500),
    cancelled_by_user_id         VARCHAR(128),
    technical_or_emergency_flag  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CHECK (participant_a_user_id <> participant_b_user_id),
    CHECK (scheduled_end_at > scheduled_start_at)
);

-- Guarantees no duplicate active session exists for the same proposal
CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_one_active_per_proposal
    ON sessions (proposal_id)
    WHERE status IN ('AWAITING_PARTNER_AGREEMENT', 'SCHEDULED', 'RESCHEDULE_PENDING_ACCEPTANCE', 'IN_PROGRESS');

CREATE INDEX IF NOT EXISTS idx_sessions_participants
    ON sessions (participant_a_user_id, participant_b_user_id, scheduled_start_at);

CREATE TABLE IF NOT EXISTS session_blocks (
    block_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID         NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    block_order       SMALLINT     NOT NULL CHECK (block_order IN (1, 2)),
    duration_minutes  SMALLINT     NOT NULL CHECK (duration_minutes BETWEEN 10 AND 90),
    teacher_user_id   VARCHAR(128) NOT NULL,
    learner_user_id   VARCHAR(128) NOT NULL,
    skill_slug        VARCHAR(80)  NOT NULL,
    topic_code        VARCHAR(64)  NOT NULL,
    UNIQUE (session_id, block_order),
    CHECK (teacher_user_id <> learner_user_id)
);

CREATE TABLE IF NOT EXISTS session_agreements (
    session_id         UUID         NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    user_id            VARCHAR(128) NOT NULL,
    agreement_version  VARCHAR(32)  NOT NULL,
    accepted           BOOLEAN      NOT NULL DEFAULT FALSE,
    accepted_at        TIMESTAMPTZ,
    PRIMARY KEY (session_id, user_id)
);

-- Observable facts of the call (token issuance, LiveKit webhook joins/leaves/drops)
CREATE TABLE IF NOT EXISTS session_attendance_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID         NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    user_id           VARCHAR(128) NOT NULL,
    event_type        VARCHAR(40)  NOT NULL CHECK (
        event_type IN (
            'ROOM_TOKEN_ISSUED',
            'PARTICIPANT_JOINED',
            'PARTICIPANT_LEFT',
            'CONNECTION_DROPPED',
            'RECONNECTED'
        )
    ),
    external_event_id VARCHAR(128) UNIQUE, -- LiveKit webhook event ID for deduplication
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    metadata_json     JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_attendance_session_user ON session_attendance_events (session_id, user_id, occurred_at);

-- Reliable HTTP delivery of session facts from Go -> Java (B14) without a message broker
CREATE TABLE IF NOT EXISTS session_outbox_facts (
    fact_report_id    VARCHAR(128) PRIMARY KEY, -- Sent as Idempotency-Key header to Java
    session_id        UUID         NOT NULL REFERENCES sessions(session_id),
    proposal_id       UUID         NOT NULL,
    payload_json      JSONB        NOT NULL,
    delivery_status   VARCHAR(24)  NOT NULL DEFAULT 'PENDING' CHECK (
        delivery_status IN ('PENDING', 'DELIVERED', 'FAILED_RETRYABLE')
    ),
    attempt_count     INT          NOT NULL DEFAULT 0,
    next_retry_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_http_status  INT,
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending_retry ON session_outbox_facts (next_retry_at)
    WHERE delivery_status IN ('PENDING', 'FAILED_RETRYABLE');

-- Request-level HTTP Idempotency-Key store for POST /sessions and PATCH /reschedule
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key   VARCHAR(128) NOT NULL,
    operation_name    VARCHAR(64)  NOT NULL,
    actor_user_id     VARCHAR(128) NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,
    response_status   INT          NOT NULL,
    response_body     JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    PRIMARY KEY (idempotency_key, operation_name, actor_user_id)
);

