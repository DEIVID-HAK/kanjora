package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
)

// PostgresSessionRepo persiste sesiones únicamente en el esquema aislado "sessions".
type PostgresSessionRepo struct {
	DB *sql.DB
}

func (r *PostgresSessionRepo) GetIdempotency(ctx context.Context, key, operation, actor string) (*IdempotentRecord, error) {
	var rec IdempotentRecord
	err := r.DB.QueryRowContext(ctx, `
		SELECT request_hash, response_status, response_body
		FROM idempotency_keys
		WHERE idempotency_key = $1 AND operation_name = $2 AND actor_user_id = $3 AND expires_at > NOW()
	`, key, operation, actor).Scan(&rec.RequestHash, &rec.ResponseStatus, &rec.ResponseBody)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &rec, err
}

func (r *PostgresSessionRepo) CreateSession(ctx context.Context, ses *SessionResponse, idemKey, reqHash, actor string) error {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	guidesJSON, _ := json.Marshal(ses.RecommendedGuideIDs)
	_, err = tx.ExecContext(ctx, `
		INSERT INTO sessions (
			session_id, proposal_id, participant_a_user_id, participant_b_user_id,
			status, scheduled_start_at, scheduled_end_at, livekit_room_name, recommended_guide_ids_json
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)
	`, ses.SessionID, ses.ProposalID, ses.Agreements[0].UserID, ses.Agreements[1].UserID,
		ses.Status, ses.ScheduledStartAt, ses.ScheduledEndAt, ses.LiveKitRoomName, string(guidesJSON))
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return newProblem(http.StatusConflict, "ACTIVE_SESSION_EXISTS", "Sesión activa existente", "Ya existe una sesión activa para esta propuesta.")
		}
		return err
	}

	for _, b := range ses.Blocks {
		_, err = tx.ExecContext(ctx, `
			INSERT INTO session_blocks (block_id, session_id, block_order, duration_minutes, teacher_user_id, learner_user_id, skill_slug, topic_code)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		`, uuid.NewString(), ses.SessionID, b.BlockOrder, b.DurationMinutes, b.TeacherUserID, b.LearnerUserID, b.SkillSlug, b.TopicCode)
		if err != nil {
			return err
		}
	}

	for _, a := range ses.Agreements {
		_, err = tx.ExecContext(ctx, `
			INSERT INTO session_agreements (session_id, user_id, agreement_version, accepted, accepted_at)
			VALUES ($1, $2, $3, $4, $5)
		`, ses.SessionID, a.UserID, a.AgreementVersion, a.Accepted, a.AcceptedAt)
		if err != nil {
			return err
		}
	}

	bodyJSON, _ := json.Marshal(ses)
	_, err = tx.ExecContext(ctx, `
		INSERT INTO idempotency_keys (idempotency_key, operation_name, actor_user_id, request_hash, response_status, response_body)
		VALUES ($1, 'CREATE_SESSION', $2, $3, 201, $4::jsonb)
	`, idemKey, actor, reqHash, string(bodyJSON))
	if err != nil {
		return err
	}

	return tx.Commit()
}

func (r *PostgresSessionRepo) GetSession(ctx context.Context, sessionID string) (*SessionResponse, error) {
	return loadSession(ctx, r.DB, sessionID)
}

func (r *PostgresSessionRepo) UpdateAgreement(ctx context.Context, sessionID, userID, version string, accepted bool, at time.Time, newStatus string) (*SessionResponse, error) {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		UPDATE session_agreements SET accepted = $1, accepted_at = $2, agreement_version = $3
		WHERE session_id = $4 AND user_id = $5
	`, accepted, at, version, sessionID, userID); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE sessions SET status = $1, updated_at = NOW() WHERE session_id = $2
	`, newStatus, sessionID); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return loadSession(ctx, r.DB, sessionID)
}

func (r *PostgresSessionRepo) Reschedule(ctx context.Context, sessionID, actor, idemKey, reqHash string, start, end time.Time, partnerID string) (*SessionResponse, error) {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		UPDATE sessions SET scheduled_start_at = $1, scheduled_end_at = $2, status = $3, updated_at = NOW()
		WHERE session_id = $4
	`, start, end, StatusReschedulePending, sessionID); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE session_agreements SET accepted = TRUE, accepted_at = COALESCE(accepted_at, NOW())
		WHERE session_id = $1 AND user_id = $2
	`, sessionID, actor); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE session_agreements SET accepted = FALSE, accepted_at = NULL
		WHERE session_id = $1 AND user_id = $2
	`, sessionID, partnerID); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}

	ses, err := loadSession(ctx, r.DB, sessionID)
	if err != nil {
		return nil, err
	}
	bodyJSON, _ := json.Marshal(ses)
	_, _ = r.DB.ExecContext(ctx, `
		INSERT INTO idempotency_keys (idempotency_key, operation_name, actor_user_id, request_hash, response_status, response_body)
		VALUES ($1, 'RESCHEDULE_SESSION', $2, $3, 200, $4::jsonb) ON CONFLICT DO NOTHING
	`, idemKey, actor, reqHash, string(bodyJSON))
	return ses, nil
}

func (r *PostgresSessionRepo) CancelAndEnqueueFact(ctx context.Context, sessionID, actor, reason string, techOrEmerg bool, fact OutboxFact) (*SessionResponse, error) {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		UPDATE sessions SET status = $1, cancellation_reason = $2, cancelled_by_user_id = $3, technical_or_emergency_flag = $4, updated_at = NOW()
		WHERE session_id = $5
	`, StatusCancelled, reason, actor, techOrEmerg, sessionID); err != nil {
		return nil, err
	}
	if err := insertOutboxTx(ctx, tx, fact); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return loadSession(ctx, r.DB, sessionID)
}

func (r *PostgresSessionRepo) TransitionToInProgressAndLogToken(ctx context.Context, sessionID, actor string, at time.Time) (*SessionResponse, error) {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		UPDATE sessions SET status = $1, updated_at = NOW()
		WHERE session_id = $2 AND status = $3
	`, StatusInProgress, sessionID, StatusScheduled); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO session_attendance_events (event_id, session_id, user_id, event_type, occurred_at)
		VALUES ($1, $2, $3, 'ROOM_TOKEN_ISSUED', $4)
	`, uuid.NewString(), sessionID, actor, at); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return loadSession(ctx, r.DB, sessionID)
}

func (r *PostgresSessionRepo) CloseSessionAndEnqueueFact(ctx context.Context, sessionID, terminalState string, fact OutboxFact) (*SessionResponse, error) {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		UPDATE sessions SET status = $1, updated_at = NOW() WHERE session_id = $2
	`, terminalState, sessionID); err != nil {
		return nil, err
	}
	if err := insertOutboxTx(ctx, tx, fact); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return loadSession(ctx, r.DB, sessionID)
}

func (r *PostgresSessionRepo) ListPendingOutboxFacts(ctx context.Context, limit int) ([]OutboxFact, error) {
	rows, err := r.DB.QueryContext(ctx, `
		SELECT fact_report_id, session_id, proposal_id, payload_json
		FROM session_outbox_facts
		WHERE delivery_status IN ('PENDING', 'FAILED_RETRYABLE') AND next_retry_at <= NOW()
		ORDER BY created_at ASC LIMIT $1
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []OutboxFact
	for rows.Next() {
		var f OutboxFact
		var raw []byte
		if err := rows.Scan(&f.FactReportID, &f.SessionID, &f.ProposalID, &raw); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(raw, &f.Payload)
		out = append(out, f)
	}
	return out, rows.Err()
}

func (r *PostgresSessionRepo) MarkOutboxDelivered(ctx context.Context, factReportID string) error {
	_, err := r.DB.ExecContext(ctx, `
		UPDATE session_outbox_facts SET delivery_status = 'DELIVERED', delivered_at = NOW(), attempt_count = attempt_count + 1
		WHERE fact_report_id = $1
	`, factReportID)
	return err
}

func (r *PostgresSessionRepo) MarkOutboxFailed(ctx context.Context, factReportID, errMsg string) error {
	_, err := r.DB.ExecContext(ctx, `
		UPDATE session_outbox_facts
		SET delivery_status = 'FAILED_RETRYABLE', attempt_count = attempt_count + 1,
		    last_error = $2, next_retry_at = NOW() + INTERVAL '30 seconds'
		WHERE fact_report_id = $1
	`, factReportID, errMsg)
	return err
}

func insertOutboxTx(ctx context.Context, tx *sql.Tx, fact OutboxFact) error {
	payloadJSON, _ := json.Marshal(fact.Payload)
	_, err := tx.ExecContext(ctx, `
		INSERT INTO session_outbox_facts (fact_report_id, session_id, proposal_id, payload_json, delivery_status)
		VALUES ($1, $2, $3, $4::jsonb, 'PENDING')
	`, fact.FactReportID, fact.SessionID, fact.ProposalID, string(payloadJSON))
	return err
}

func loadSession(ctx context.Context, db *sql.DB, sessionID string) (*SessionResponse, error) {
	var ses SessionResponse
	var guidesRaw []byte
	err := db.QueryRowContext(ctx, `
		SELECT session_id, proposal_id, status, scheduled_start_at, scheduled_end_at, livekit_room_name, recommended_guide_ids_json
		FROM sessions WHERE session_id = $1
	`, sessionID).Scan(&ses.SessionID, &ses.ProposalID, &ses.Status, &ses.ScheduledStartAt, &ses.ScheduledEndAt, &ses.LiveKitRoomName, &guidesRaw)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, newProblem(http.StatusNotFound, "SESSION_NOT_FOUND", "Sesión no encontrada", "La sesión solicitada no existe.")
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal(guidesRaw, &ses.RecommendedGuideIDs)
	if ses.RecommendedGuideIDs == nil {
		ses.RecommendedGuideIDs = []string{}
	}

	bRows, err := db.QueryContext(ctx, `
		SELECT block_order, duration_minutes, teacher_user_id, learner_user_id, skill_slug, topic_code
		FROM session_blocks WHERE session_id = $1 ORDER BY block_order ASC
	`, sessionID)
	if err != nil {
		return nil, err
	}
	defer bRows.Close()
	for bRows.Next() {
		var b SessionBlock
		if err := bRows.Scan(&b.BlockOrder, &b.DurationMinutes, &b.TeacherUserID, &b.LearnerUserID, &b.SkillSlug, &b.TopicCode); err != nil {
			return nil, err
		}
		ses.Blocks = append(ses.Blocks, b)
	}

	aRows, err := db.QueryContext(ctx, `
		SELECT user_id, accepted, accepted_at, agreement_version
		FROM session_agreements WHERE session_id = $1 ORDER BY user_id ASC
	`, sessionID)
	if err != nil {
		return nil, err
	}
	defer aRows.Close()
	for aRows.Next() {
		var a ParticipantAgreementState
		if err := aRows.Scan(&a.UserID, &a.Accepted, &a.AcceptedAt, &a.AgreementVersion); err != nil {
			return nil, err
		}
		ses.Agreements = append(ses.Agreements, a)
	}
	return &ses, nil
}

