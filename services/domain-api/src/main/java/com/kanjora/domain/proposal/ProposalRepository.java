package com.kanjora.domain.proposal;

import com.kanjora.domain.proposal.ProposalDtos.ExchangeProposalResponse;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProposalRepository {

    private final JdbcClient jdbcClient;

    public ProposalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean hasActiveProposalBetween(String userA, String userB) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM exchange_proposals
                WHERE status IN ('PENDING_RECIPIENT', 'ACCEPTED')
                  AND (
                        (proposer_user_id = :userA AND recipient_user_id = :userB)
                     OR (proposer_user_id = :userB AND recipient_user_id = :userA)
                  )
                """)
                .param("userA", userA)
                .param("userB", userB)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public ExchangeProposalResponse insertProposal(
            UUID proposalId,
            String proposerUserId,
            String recipientUserId,
            UUID proposerTeachesSkillId,
            UUID proposerTeachesLevelId,
            UUID proposerQualificationId,
            String proposerTopicCode,
            UUID recipientTeachesSkillId,
            UUID recipientTeachesLevelId,
            UUID recipientQualificationId,
            String recipientTopicCode,
            double matchScore,
            String matchExplanationJson) {

        return jdbcClient.sql("""
                INSERT INTO exchange_proposals (
                    proposal_id,
                    proposer_user_id,
                    recipient_user_id,
                    proposer_teaches_skill_id,
                    proposer_teaches_level_id,
                    proposer_qualification_id,
                    proposer_topic_code,
                    recipient_teaches_skill_id,
                    recipient_teaches_level_id,
                    recipient_qualification_id,
                    recipient_topic_code,
                    status,
                    match_score,
                    match_explanation_json
                ) VALUES (
                    :proposalId,
                    :proposerUserId,
                    :recipientUserId,
                    :proposerTeachesSkillId,
                    :proposerTeachesLevelId,
                    :proposerQualificationId,
                    :proposerTopicCode,
                    :recipientTeachesSkillId,
                    :recipientTeachesLevelId,
                    :recipientQualificationId,
                    :recipientTopicCode,
                    'PENDING_RECIPIENT',
                    :matchScore,
                    CAST(:matchExplanationJson AS jsonb)
                )
                RETURNING proposal_id, status, proposer_user_id, recipient_user_id, created_at, responded_at
                """)
                .param("proposalId", proposalId)
                .param("proposerUserId", proposerUserId)
                .param("recipientUserId", recipientUserId)
                .param("proposerTeachesSkillId", proposerTeachesSkillId)
                .param("proposerTeachesLevelId", proposerTeachesLevelId)
                .param("proposerQualificationId", proposerQualificationId)
                .param("proposerTopicCode", proposerTopicCode)
                .param("recipientTeachesSkillId", recipientTeachesSkillId)
                .param("recipientTeachesLevelId", recipientTeachesLevelId)
                .param("recipientQualificationId", recipientQualificationId)
                .param("recipientTopicCode", recipientTopicCode)
                .param("matchScore", matchScore)
                .param("matchExplanationJson", matchExplanationJson)
                .query(ProposalRepository::mapProposal)
                .single();
    }

    public Optional<ExchangeProposalResponse> findById(UUID proposalId) {
        return jdbcClient.sql("""
                SELECT proposal_id, status, proposer_user_id, recipient_user_id, created_at, responded_at
                FROM exchange_proposals
                WHERE proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query(ProposalRepository::mapProposal)
                .optional();
    }

    public ExchangeProposalResponse updateDecision(UUID proposalId, String newStatus, String rejectionReason) {
        return jdbcClient.sql("""
                UPDATE exchange_proposals
                SET status = :newStatus,
                    rejection_reason = :rejectionReason,
                    responded_at = NOW()
                WHERE proposal_id = :proposalId
                RETURNING proposal_id, status, proposer_user_id, recipient_user_id, created_at, responded_at
                """)
                .param("newStatus", newStatus)
                .param("rejectionReason", rejectionReason, Types.VARCHAR)
                .param("proposalId", proposalId)
                .query(ProposalRepository::mapProposal)
                .single();
    }

    public Optional<ProposalValidationContext> findValidationContext(UUID proposalId) {
        return jdbcClient.sql("""
                SELECT ep.proposal_id,
                       ep.status,
                       ep.proposer_user_id,
                       ep.recipient_user_id,
                       ep.proposer_teaches_skill_id,
                       ps.slug AS proposer_teaches_skill_slug,
                       ep.proposer_teaches_level_id,
                       ep.proposer_topic_code,
                       ep.proposer_qualification_id,
                       ptq.status AS proposer_qualification_status,
                       COALESCE(pg.target_level_id, ep.recipient_teaches_level_id) AS proposer_learns_level_id,
                       ep.recipient_teaches_skill_id,
                       rs.slug AS recipient_teaches_skill_slug,
                       ep.recipient_teaches_level_id,
                       ep.recipient_topic_code,
                       ep.recipient_qualification_id,
                       rtq.status AS recipient_qualification_status,
                       COALESCE(rg.target_level_id, ep.proposer_teaches_level_id) AS recipient_learns_level_id
                FROM exchange_proposals ep
                JOIN skills ps ON ps.skill_id = ep.proposer_teaches_skill_id
                JOIN skills rs ON rs.skill_id = ep.recipient_teaches_skill_id
                JOIN teaching_qualifications ptq ON ptq.qualification_id = ep.proposer_qualification_id
                JOIN teaching_qualifications rtq ON rtq.qualification_id = ep.recipient_qualification_id
                LEFT JOIN user_learning_goals pg
                       ON pg.user_id = ep.proposer_user_id AND pg.skill_id = ep.recipient_teaches_skill_id
                LEFT JOIN user_learning_goals rg
                       ON rg.user_id = ep.recipient_user_id AND rg.skill_id = ep.proposer_teaches_skill_id
                WHERE ep.proposal_id = :proposalId
                """)
                .param("proposalId", proposalId)
                .query((rs, rowNum) -> new ProposalValidationContext(
                        rs.getObject("proposal_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("proposer_user_id"),
                        rs.getString("recipient_user_id"),
                        rs.getObject("proposer_teaches_skill_id", UUID.class),
                        rs.getString("proposer_teaches_skill_slug"),
                        rs.getObject("proposer_teaches_level_id", UUID.class),
                        rs.getString("proposer_topic_code"),
                        rs.getObject("proposer_qualification_id", UUID.class),
                        rs.getString("proposer_qualification_status"),
                        rs.getObject("proposer_learns_level_id", UUID.class),
                        rs.getObject("recipient_teaches_skill_id", UUID.class),
                        rs.getString("recipient_teaches_skill_slug"),
                        rs.getObject("recipient_teaches_level_id", UUID.class),
                        rs.getString("recipient_topic_code"),
                        rs.getObject("recipient_qualification_id", UUID.class),
                        rs.getString("recipient_qualification_status"),
                        rs.getObject("recipient_learns_level_id", UUID.class)))
                .optional();
    }

    public List<UUID> findRecommendedGuideIds(UUID skillA, UUID levelA, UUID skillB, UUID levelB) {
        return jdbcClient.sql("""
                SELECT guide_id
                FROM study_guides
                WHERE is_active = TRUE
                  AND is_free = TRUE
                  AND (
                        (skill_id = :skillA AND (level_id = :levelA OR level_id IS NULL))
                     OR (skill_id = :skillB AND (level_id = :levelB OR level_id IS NULL))
                  )
                ORDER BY created_at ASC
                """)
                .param("skillA", skillA)
                .param("levelA", levelA)
                .param("skillB", skillB)
                .param("levelB", levelB)
                .query(UUID.class)
                .list();
    }

    private static ExchangeProposalResponse mapProposal(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExchangeProposalResponse(
                rs.getObject("proposal_id", UUID.class),
                rs.getString("status"),
                rs.getString("proposer_user_id"),
                rs.getString("recipient_user_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("responded_at", OffsetDateTime.class));
    }

    public record ProposalValidationContext(
            UUID proposalId,
            String status,
            String proposerUserId,
            String recipientUserId,
            UUID proposerTeachesSkillId,
            String proposerTeachesSkillSlug,
            UUID proposerTeachesLevelId,
            String proposerTopicCode,
            UUID proposerQualificationId,
            String proposerQualificationStatus,
            UUID proposerLearnsLevelId,
            UUID recipientTeachesSkillId,
            String recipientTeachesSkillSlug,
            UUID recipientTeachesLevelId,
            String recipientTopicCode,
            UUID recipientQualificationId,
            String recipientQualificationStatus,
            UUID recipientLearnsLevelId) {
    }
}

