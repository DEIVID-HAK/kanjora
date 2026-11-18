-- =============================================================================
-- Kanjora Domain API - PostgreSQL Schema V1 (B05 - B15)
-- Owner: kanjora_domain_app (schema: domain via role search_path)
-- Rules enforced at DB level:
--   1. Skills, categories, levels, topics, and guides are configurable data.
--   2. Teaching qualification is strictly per (user_id, skill_id, level_id).
--   3. Proposals require valid APPROVED qualifications on both sides.
--   4. Session fact reports are idempotent via fact_report_id PK.
--   5. Bilateral confirmations resolve to CONFIRMED or PENDING_REVIEW;
--      no active credit balances, VIP rankings, or automatic sanctions exist.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Configurable Skill Catalog (B05)
-- -----------------------------------------------------------------------------
CREATE TABLE skill_categories (
    category_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE skills (
    skill_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID         NOT NULL REFERENCES skill_categories(category_id),
    slug        VARCHAR(80)  NOT NULL UNIQUE,
    name        VARCHAR(140) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_skills_category_active ON skills (category_id) WHERE is_active = TRUE;

CREATE TABLE skill_levels (
    level_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id    UUID        NOT NULL REFERENCES skills(skill_id) ON DELETE CASCADE,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(80) NOT NULL,
    rank_order  SMALLINT    NOT NULL CHECK (rank_order >= 1),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (skill_id, code),
    UNIQUE (skill_id, rank_order),
    UNIQUE (level_id, skill_id)
);

CREATE TABLE skill_topics (
    topic_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id    UUID         NOT NULL REFERENCES skills(skill_id) ON DELETE CASCADE,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(140) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (skill_id, code),
    UNIQUE (topic_id, skill_id)
);

CREATE TABLE study_guides (
    guide_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id          UUID         REFERENCES skills(skill_id) ON DELETE CASCADE,
    level_id          UUID,
    guide_type        VARCHAR(32)  NOT NULL CHECK (guide_type IN ('METHODOLOGY', 'BASIC_STUDY_ROUTE', 'SESSION_GUIDE')),
    title             VARCHAR(180) NOT NULL,
    content_markdown  TEXT         NOT NULL,
    is_free           BOOLEAN      NOT NULL DEFAULT TRUE CHECK (is_free = TRUE),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    FOREIGN KEY (level_id, skill_id) REFERENCES skill_levels(level_id, skill_id)
);

-- -----------------------------------------------------------------------------
-- 2. User Profiles & Availability (B05)
-- -----------------------------------------------------------------------------
CREATE TABLE user_profiles (
    user_id       VARCHAR(128) PRIMARY KEY, -- OIDC JWT 'sub' claim
    display_name  VARCHAR(80)  NOT NULL,
    bio           VARCHAR(500),
    timezone      VARCHAR(64)  NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_availability_slots (
    slot_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id) ON DELETE CASCADE,
    day_of_week       SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time_local  TIME         NOT NULL,
    end_time_local    TIME         NOT NULL,
    CHECK (end_time_local > start_time_local)
);

CREATE INDEX idx_availability_user_day ON user_availability_slots (user_id, day_of_week, start_time_local);

CREATE TABLE user_learning_goals (
    goal_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id) ON DELETE CASCADE,
    skill_id         UUID         NOT NULL REFERENCES skills(skill_id),
    target_level_id  UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, skill_id),
    FOREIGN KEY (target_level_id, skill_id) REFERENCES skill_levels(level_id, skill_id)
);

CREATE TABLE user_learning_goal_topics (
    goal_id   UUID NOT NULL REFERENCES user_learning_goals(goal_id) ON DELETE CASCADE,
    topic_id  UUID NOT NULL,
    skill_id  UUID NOT NULL,
    PRIMARY KEY (goal_id, topic_id),
    FOREIGN KEY (topic_id, skill_id) REFERENCES skill_topics(topic_id, skill_id)
);

CREATE TABLE user_teaching_offers (
    offer_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id) ON DELETE CASCADE,
    skill_id    UUID         NOT NULL REFERENCES skills(skill_id),
    level_id    UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, skill_id, level_id),
    FOREIGN KEY (level_id, skill_id) REFERENCES skill_levels(level_id, skill_id)
);

CREATE TABLE user_teaching_offer_topics (
    offer_id  UUID NOT NULL REFERENCES user_teaching_offers(offer_id) ON DELETE CASCADE,
    topic_id  UUID NOT NULL,
    skill_id  UUID NOT NULL,
    PRIMARY KEY (offer_id, topic_id),
    FOREIGN KEY (topic_id, skill_id) REFERENCES skill_topics(topic_id, skill_id)
);

-- -----------------------------------------------------------------------------
-- 3. Teaching Qualification & Human Mini-Class Review (B06 - B07)
-- -----------------------------------------------------------------------------
CREATE TABLE teaching_qualifications (
    qualification_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id) ON DELETE CASCADE,
    skill_id                 UUID         NOT NULL REFERENCES skills(skill_id),
    level_id                 UUID         NOT NULL,
    status                   VARCHAR(32)  NOT NULL CHECK (
        status IN ('IN_PROGRESS', 'PENDING_HUMAN_REVIEW', 'APPROVED', 'REJECTED', 'REVOKED')
    ),
    methodology_acknowledged BOOLEAN      NOT NULL DEFAULT FALSE,
    practical_test_passed    BOOLEAN      NOT NULL DEFAULT FALSE,
    practical_test_reference VARCHAR(255),
    approved_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, skill_id, level_id),
    UNIQUE (qualification_id, user_id, skill_id, level_id),
    FOREIGN KEY (level_id, skill_id) REFERENCES skill_levels(level_id, skill_id),
    CHECK (status <> 'APPROVED' OR (methodology_acknowledged = TRUE AND practical_test_passed = TRUE))
);

CREATE INDEX idx_qualifications_approved_lookup
    ON teaching_qualifications (skill_id, level_id, user_id)
    WHERE status = 'APPROVED';

CREATE TABLE mini_class_submissions (
    submission_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    qualification_id         UUID         NOT NULL REFERENCES teaching_qualifications(qualification_id) ON DELETE CASCADE,
    attempt_number           INT          NOT NULL CHECK (attempt_number >= 1),
    lesson_objective         VARCHAR(500) NOT NULL,
    recording_or_artifact_url TEXT        NOT NULL,
    self_check_summary       VARCHAR(1000) NOT NULL,
    review_status            VARCHAR(24)  NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (
        review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')
    ),
    reviewer_user_id         VARCHAR(128),
    reviewer_comments        VARCHAR(2000),
    submitted_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_at              TIMESTAMPTZ,
    UNIQUE (qualification_id, attempt_number)
);

CREATE UNIQUE INDEX idx_one_pending_submission_per_qual
    ON mini_class_submissions (qualification_id)
    WHERE review_status = 'PENDING_REVIEW';

-- -----------------------------------------------------------------------------
-- 4. Reciprocal Exchange Proposals & Explainable Match Snapshot (B08 - B10)
-- -----------------------------------------------------------------------------
CREATE TABLE exchange_proposals (
    proposal_id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposer_user_id                VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id),
    recipient_user_id               VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id),
    proposer_teaches_skill_id       UUID         NOT NULL REFERENCES skills(skill_id),
    proposer_teaches_level_id       UUID         NOT NULL,
    proposer_qualification_id       UUID         NOT NULL,
    proposer_topic_code             VARCHAR(64)  NOT NULL DEFAULT 'general',
    recipient_teaches_skill_id      UUID         NOT NULL REFERENCES skills(skill_id),
    recipient_teaches_level_id      UUID         NOT NULL,
    recipient_qualification_id      UUID         NOT NULL,
    recipient_topic_code            VARCHAR(64)  NOT NULL DEFAULT 'general',
    status                          VARCHAR(32)  NOT NULL CHECK (
        status IN ('PENDING_RECIPIENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'COMPLETED', 'PENDING_REVIEW')
    ),
    match_score                     NUMERIC(8,2) NOT NULL DEFAULT 0,
    match_explanation_json          JSONB        NOT NULL,
    rejection_reason                VARCHAR(500),
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    responded_at                    TIMESTAMPTZ,
    CHECK (proposer_user_id <> recipient_user_id),
    FOREIGN KEY (proposer_qualification_id, proposer_user_id, proposer_teaches_skill_id, proposer_teaches_level_id)
        REFERENCES teaching_qualifications(qualification_id, user_id, skill_id, level_id),
    FOREIGN KEY (recipient_qualification_id, recipient_user_id, recipient_teaches_skill_id, recipient_teaches_level_id)
        REFERENCES teaching_qualifications(qualification_id, user_id, skill_id, level_id)
);

CREATE INDEX idx_proposals_participants ON exchange_proposals (proposer_user_id, recipient_user_id, status);

-- -----------------------------------------------------------------------------
-- 5. Session Facts Ingestion (Go -> Java) & Bilateral Confirmation (B14 - B15)
-- -----------------------------------------------------------------------------
CREATE TABLE session_fact_reports (
    fact_report_id                VARCHAR(128) PRIMARY KEY, -- Idempotency-Key from Go
    proposal_id                   UUID         NOT NULL REFERENCES exchange_proposals(proposal_id),
    session_id                    UUID         NOT NULL,
    terminal_session_state        VARCHAR(40)  NOT NULL CHECK (
        terminal_session_state IN (
            'COMPLETED_OBSERVED',
            'NO_SHOW_OBSERVED',
            'ABANDONED_MID_CALL',
            'TECHNICAL_FAILURE_OBSERVED',
            'CANCELLED_BEFORE_START'
        )
    ),
    occurred_at                   TIMESTAMPTZ  NOT NULL,
    participants_attendance_json  JSONB        NOT NULL,
    technical_incident_observed   BOOLEAN      NOT NULL DEFAULT FALSE,
    incident_code                 VARCHAR(64),
    received_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_facts_proposal ON session_fact_reports (proposal_id, session_id);

CREATE TABLE exchange_resolutions (
    proposal_id         UUID PRIMARY KEY REFERENCES exchange_proposals(proposal_id),
    session_id          UUID         NOT NULL,
    resolution_status   VARCHAR(40)  NOT NULL CHECK (
        resolution_status IN (
            'AWAITING_BILATERAL_CONFIRMATION',
            'AWAITING_PARTNER_CONFIRMATION',
            'CONFIRMED',
            'PENDING_REVIEW',
            'CANCELLED_NO_PENALTY'
        )
    ),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    reviewer_user_id    VARCHAR(128),
    resolution_notes    TEXT
);

CREATE TABLE exchange_confirmations (
    confirmation_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id                 UUID         NOT NULL REFERENCES exchange_proposals(proposal_id),
    session_id                  UUID         NOT NULL,
    user_id                     VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id),
    outcome_declaration         VARCHAR(40)  NOT NULL CHECK (
        outcome_declaration IN (
            'COMPLETED',
            'INCOMPLETE_TECHNICAL_ISSUE',
            'PARTNER_NO_SHOW',
            'PARTNER_ABANDONED',
            'METHODOLOGY_NOT_FOLLOWED'
        )
    ),
    both_blocks_executed        BOOLEAN      NOT NULL,
    learned_skill_progress_note VARCHAR(1000),
    incident_reason             VARCHAR(200),
    incident_details            VARCHAR(2000),
    would_repeat_with_partner   BOOLEAN      NOT NULL,
    confirmed_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (proposal_id, session_id, user_id)
);

-- Append-only factual log of bilaterally confirmed exchanges for post-pilot study.
-- Does NOT activate credit balances, redemptions, VIP priorities, or automatic penalties.
CREATE TABLE confirmed_exchange_facts_log (
    fact_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id            UUID         NOT NULL UNIQUE REFERENCES exchange_proposals(proposal_id),
    session_id             UUID         NOT NULL UNIQUE,
    participant_a_user_id  VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id),
    participant_b_user_id  VARCHAR(128) NOT NULL REFERENCES user_profiles(user_id),
    confirmed_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    fact_payload_json      JSONB        NOT NULL
);

