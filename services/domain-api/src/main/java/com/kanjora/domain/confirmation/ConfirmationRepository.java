package com.kanjora.domain.confirmation;

import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationRequest;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.ExchangeHistoryItemResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.OutcomeDeclaration;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.PilotMetricsResponse;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportRequest;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConfirmationRepository {

    private final JdbcClient jdbcClient;

    public ConfirmationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<ProposalParticipants> findProposalParticipants(UUID proposalId) {
        return jdbcClient.sql("""
                SELECT proposal_id, proposer_user_id, recipient_user_id, status
                FROM exchange_proposals
                WHERE proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query((rs, rowNum) -> new ProposalParticipants(
                        rs.getObject("proposal_id", UUID.class),
                        rs.getString("proposer_user_id"),
                        rs.getString("recipient_user_id"),
                        rs.getString("status")))
                .optional();
    }

    public Optional<ExistingFactReport> findFactReportById(String factReportId) {
        return jdbcClient.sql("""
                SELECT r.fact_report_id, r.proposal_id, er.resolution_status
                FROM session_fact_reports r
                LEFT JOIN exchange_resolutions er ON er.proposal_id = r.proposal_id
                WHERE r.fact_report_id = :factReportId
                """)
                .param("factReportId", factReportId)
                .query((rs, rowNum) -> new ExistingFactReport(
                        rs.getString("fact_report_id"),
                        rs.getObject("proposal_id", UUID.class),
                        rs.getString("resolution_status")))
                .optional();
    }

    public void insertFactReport(SessionFactsReportRequest req, String attendanceJson) {
        jdbcClient.sql("""
                INSERT INTO session_fact_reports (
                    fact_report_id, proposal_id, session_id, terminal_session_state,
                    occurred_at, participants_attendance_json, technical_incident_observed, incident_code
                ) VALUES (
                    :factReportId, :proposalId, :sessionId, :terminalState,
                    :occurredAt, CAST(:attendanceJson AS jsonb), :technicalIncident, :incidentCode
                )
                """)
                .param("factReportId", req.factReportId())
                .param("proposalId", req.proposalId())
                .param("sessionId", req.sessionId())
                .param("terminalState", req.terminalSessionState().name())
                .param("occurredAt", req.occurredAt())
                .param("attendanceJson", attendanceJson)
                .param("technicalIncident", req.technicalIncidentObserved())
                .param("incidentCode", req.incidentCode(), Types.VARCHAR)
                .update();
    }

    public void upsertResolution(UUID proposalId, UUID sessionId, String resolutionStatus, boolean markResolvedNow) {
        jdbcClient.sql("""
                INSERT INTO exchange_resolutions (proposal_id, session_id, resolution_status, updated_at, resolved_at)
                VALUES (
                    :proposalId,
                    :sessionId,
                    :resolutionStatus,
                    NOW(),
                    CASE WHEN :markResolvedNow THEN NOW() ELSE NULL END
                )
                ON CONFLICT (proposal_id) DO UPDATE
                SET session_id = EXCLUDED.session_id,
                    resolution_status = EXCLUDED.resolution_status,
                    updated_at = NOW(),
                    resolved_at = CASE WHEN :markResolvedNow THEN NOW() ELSE NULL END
                """)
                .param("proposalId", proposalId)
                .param("sessionId", sessionId)
                .param("resolutionStatus", resolutionStatus)
                .param("markResolvedNow", markResolvedNow)
                .update();
    }

    public void updateProposalStatus(UUID proposalId, String status) {
        jdbcClient.sql("UPDATE exchange_proposals SET status = :status WHERE proposal_id = :proposalId")
                .param("status", status)
                .param("proposalId", proposalId)
                .update();
    }

    public Optional<ResolutionRecord> findResolutionByProposal(UUID proposalId) {
        return jdbcClient.sql("""
                SELECT proposal_id, session_id, resolution_status
                FROM exchange_resolutions
                WHERE proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query((rs, rowNum) -> new ResolutionRecord(
                        rs.getObject("proposal_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("resolution_status")))
                .optional();
    }

    public boolean hasBlockingSessionIncident(UUID proposalId, UUID sessionId) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM session_fact_reports
                WHERE proposal_id = :proposalId
                  AND session_id = :sessionId
                  AND (
                        terminal_session_state IN ('NO_SHOW_OBSERVED', 'ABANDONED_MID_CALL', 'TECHNICAL_FAILURE_OBSERVED')
                     OR technical_incident_observed = TRUE
                  )
                """)
                .param("proposalId", proposalId)
                .param("sessionId", sessionId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public OffsetDateTime upsertConfirmation(UUID proposalId, String userId, BilateralConfirmationRequest req) {
        return jdbcClient.sql("""
                INSERT INTO exchange_confirmations (
                    confirmation_id, proposal_id, session_id, user_id,
                    outcome_declaration, both_blocks_executed,
                    learned_skill_progress_note, incident_reason, incident_details,
                    would_repeat_with_partner, confirmed_at
                ) VALUES (
                    :confirmationId, :proposalId, :sessionId, :userId,
                    :outcomeDeclaration, :bothBlocksExecuted,
                    :progressNote, :incidentReason, :incidentDetails,
                    :wouldRepeat, NOW()
                )
                ON CONFLICT (proposal_id, session_id, user_id) DO UPDATE
                SET outcome_declaration = EXCLUDED.outcome_declaration,
                    both_blocks_executed = EXCLUDED.both_blocks_executed,
                    learned_skill_progress_note = EXCLUDED.learned_skill_progress_note,
                    incident_reason = EXCLUDED.incident_reason,
                    incident_details = EXCLUDED.incident_details,
                    would_repeat_with_partner = EXCLUDED.would_repeat_with_partner,
                    confirmed_at = NOW()
                RETURNING confirmed_at
                """)
                .param("confirmationId", UUID.randomUUID())
                .param("proposalId", proposalId)
                .param("sessionId", req.sessionId())
                .param("userId", userId)
                .param("outcomeDeclaration", req.outcomeDeclaration().name())
                .param("bothBlocksExecuted", req.bothBlocksExecuted())
                .param("progressNote", req.learnedSkillProgressNote(), Types.VARCHAR)
                .param("incidentReason", req.incidentReason(), Types.VARCHAR)
                .param("incidentDetails", req.incidentDetails(), Types.VARCHAR)
                .param("wouldRepeat", req.wouldRepeatWithPartner())
                .query(OffsetDateTime.class)
                .single();
    }

    public List<ConfirmationEntry> findConfirmations(UUID proposalId, UUID sessionId) {
        return jdbcClient.sql("""
                SELECT user_id, outcome_declaration, both_blocks_executed, confirmed_at
                FROM exchange_confirmations
                WHERE proposal_id = :proposalId AND session_id = :sessionId
                """)
                .param("proposalId", proposalId)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new ConfirmationEntry(
                        rs.getString("user_id"),
                        OutcomeDeclaration.valueOf(rs.getString("outcome_declaration")),
                        rs.getBoolean("both_blocks_executed"),
                        rs.getObject("confirmed_at", OffsetDateTime.class)))
                .list();
    }

    public void appendConfirmedExchangeFact(
            UUID proposalId,
            UUID sessionId,
            String participantAUserId,
            String participantBUserId,
            String factPayloadJson) {

        jdbcClient.sql("""
                INSERT INTO confirmed_exchange_facts_log (
                    fact_id, proposal_id, session_id,
                    participant_a_user_id, participant_b_user_id,
                    fact_payload_json
                ) VALUES (
                    :factId, :proposalId, :sessionId,
                    :participantA, :participantB,
                    CAST(:factPayloadJson AS jsonb)
                )
                ON CONFLICT (proposal_id) DO NOTHING
                """)
                .param("factId", UUID.randomUUID())
                .param("proposalId", proposalId)
                .param("sessionId", sessionId)
                .param("participantA", participantAUserId)
                .param("participantB", participantBUserId)
                .param("factPayloadJson", factPayloadJson)
                .update();
    }

    public List<ExchangeHistoryItemResponse> findUserExchangeHistory(String userId) {
        return jdbcClient.sql("""
                SELECT ep.proposal_id,
                       er.session_id,
                       CASE WHEN ep.proposer_user_id = :userId THEN ep.recipient_user_id ELSE ep.proposer_user_id END AS partner_user_id,
                       partner_prof.display_name AS partner_display_name,
                       CASE WHEN ep.proposer_user_id = :userId THEN ps.slug ELSE rs.slug END AS my_teaching_skill_slug,
                       CASE WHEN ep.proposer_user_id = :userId THEN rs.slug ELSE ps.slug END AS partner_teaching_skill_slug,
                       ep.status AS proposal_status,
                       er.resolution_status,
                       ec.outcome_declaration AS my_outcome_declaration,
                       ec.learned_skill_progress_note AS my_progress_note,
                       ec.would_repeat_with_partner AS can_repeat_with_partner,
                       ep.created_at,
                       er.resolved_at
                FROM exchange_proposals ep
                JOIN skills ps ON ps.skill_id = ep.proposer_teaches_skill_id
                JOIN skills rs ON rs.skill_id = ep.recipient_teaches_skill_id
                JOIN user_profiles partner_prof
                  ON partner_prof.user_id = CASE WHEN ep.proposer_user_id = :userId THEN ep.recipient_user_id ELSE ep.proposer_user_id END
                LEFT JOIN exchange_resolutions er ON er.proposal_id = ep.proposal_id
                LEFT JOIN exchange_confirmations ec
                       ON ec.proposal_id = ep.proposal_id
                      AND ec.session_id = er.session_id
                      AND ec.user_id = :userId
                WHERE ep.proposer_user_id = :userId OR ep.recipient_user_id = :userId
                ORDER BY ep.created_at DESC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new ExchangeHistoryItemResponse(
                        rs.getObject("proposal_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("partner_user_id"),
                        rs.getString("partner_display_name"),
                        rs.getString("my_teaching_skill_slug"),
                        rs.getString("partner_teaching_skill_slug"),
                        rs.getString("proposal_status"),
                        rs.getString("resolution_status"),
                        rs.getString("my_outcome_declaration"),
                        rs.getString("my_progress_note"),
                        rs.getObject("can_repeat_with_partner", Boolean.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("resolved_at", OffsetDateTime.class)))
                .list();
    }

    public PilotMetricsResponse computePilotMetrics() {
        return jdbcClient.sql("""
                SELECT
                    (SELECT COUNT(*) FROM user_profiles WHERE is_active = TRUE) AS profiles_completed,
                    (SELECT COUNT(*) FROM teaching_qualifications) AS qualifications_submitted,
                    (SELECT COUNT(*) FROM teaching_qualifications WHERE status = 'APPROVED') AS qualifications_approved,
                    (SELECT COUNT(*) FROM exchange_proposals) AS proposals_created,
                    (SELECT COUNT(*) FROM exchange_proposals WHERE status IN ('ACCEPTED', 'COMPLETED', 'PENDING_REVIEW')) AS proposals_accepted,
                    (SELECT COUNT(*) FROM confirmed_exchange_facts_log) AS exchanges_confirmed,
                    (SELECT COUNT(*) FROM exchange_resolutions WHERE resolution_status = 'PENDING_REVIEW') AS exchanges_pending_review,
                    (SELECT COUNT(*) FROM exchange_resolutions WHERE resolution_status = 'CANCELLED_NO_PENALTY') AS cancelled_no_penalty_count
                """)
                .query((rs, rowNum) -> new PilotMetricsResponse(
                        rs.getLong("profiles_completed"),
                        rs.getLong("qualifications_submitted"),
                        rs.getLong("qualifications_approved"),
                        rs.getLong("proposals_created"),
                        rs.getLong("proposals_accepted"),
                        rs.getLong("exchanges_confirmed"),
                        rs.getLong("exchanges_pending_review"),
                        rs.getLong("cancelled_no_penalty_count")))
                .single();
    }

    public record ProposalParticipants(UUID proposalId, String proposerUserId, String recipientUserId, String status) {
    }

    public record ExistingFactReport(String factReportId, UUID proposalId, String resolutionStatus) {
    }

    public record ResolutionRecord(UUID proposalId, UUID sessionId, String resolutionStatus) {
    }

    public record ConfirmationEntry(
            String userId,
            OutcomeDeclaration outcomeDeclaration,
            boolean bothBlocksExecuted,
            OffsetDateTime confirmedAt) {
    }
}

