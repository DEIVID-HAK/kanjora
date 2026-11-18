package com.kanjora.domain.qualification;

import com.kanjora.domain.qualification.QualificationDtos.MiniClassSubmissionRequest;
import com.kanjora.domain.qualification.QualificationDtos.PendingSubmissionResponse;
import com.kanjora.domain.qualification.QualificationDtos.QualificationResponse;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QualificationRepository {

    private final JdbcClient jdbcClient;

    public QualificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean userProfileExists(String userId) {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM user_profiles WHERE user_id = :userId AND is_active = TRUE")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public Optional<QualificationResponse> findById(UUID qualificationId) {
        return jdbcClient.sql(baseSelectQuery() + " WHERE tq.qualification_id = :qualificationId")
                .param("qualificationId", qualificationId)
                .query(QualificationRepository::mapQualification)
                .optional();
    }

    public Optional<QualificationResponse> findByUserSkillLevel(String userId, UUID skillId, UUID levelId) {
        return jdbcClient.sql(baseSelectQuery()
                        + " WHERE tq.user_id = :userId AND tq.skill_id = :skillId AND tq.level_id = :levelId")
                .param("userId", userId)
                .param("skillId", skillId)
                .param("levelId", levelId)
                .query(QualificationRepository::mapQualification)
                .optional();
    }

    public UUID upsertProgress(
            String userId,
            UUID skillId,
            UUID levelId,
            boolean methodologyAcknowledged,
            boolean practicalTestPassed,
            String practicalTestReference) {

        Optional<QualificationResponse> existing = findByUserSkillLevel(userId, skillId, levelId);
        if (existing.isPresent()) {
            UUID qualId = existing.get().qualificationId();
            if ("IN_PROGRESS".equals(existing.get().status()) || "REJECTED".equals(existing.get().status())) {
                jdbcClient.sql("""
                        UPDATE teaching_qualifications
                        SET methodology_acknowledged = :methodologyAcknowledged,
                            practical_test_passed = :practicalTestPassed,
                            practical_test_reference = :practicalTestReference,
                            updated_at = NOW()
                        WHERE qualification_id = :qualificationId
                        """)
                        .param("methodologyAcknowledged", methodologyAcknowledged)
                        .param("practicalTestPassed", practicalTestPassed)
                        .param("practicalTestReference", practicalTestReference, Types.VARCHAR)
                        .param("qualificationId", qualId)
                        .update();
            }
            return qualId;
        }

        UUID qualificationId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO teaching_qualifications (
                    qualification_id, user_id, skill_id, level_id, status,
                    methodology_acknowledged, practical_test_passed, practical_test_reference
                ) VALUES (
                    :qualificationId, :userId, :skillId, :levelId, 'IN_PROGRESS',
                    :methodologyAcknowledged, :practicalTestPassed, :practicalTestReference
                )
                """)
                .param("qualificationId", qualificationId)
                .param("userId", userId)
                .param("skillId", skillId)
                .param("levelId", levelId)
                .param("methodologyAcknowledged", methodologyAcknowledged)
                .param("practicalTestPassed", practicalTestPassed)
                .param("practicalTestReference", practicalTestReference, Types.VARCHAR)
                .update();
        return qualificationId;
    }

    public void createMiniClassSubmission(UUID qualificationId, MiniClassSubmissionRequest req) {
        Integer nextAttempt = jdbcClient.sql("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1
                FROM mini_class_submissions
                WHERE qualification_id = :qualificationId
                """)
                .param("qualificationId", qualificationId)
                .query(Integer.class)
                .single();

        jdbcClient.sql("""
                INSERT INTO mini_class_submissions (
                    submission_id, qualification_id, attempt_number,
                    lesson_objective, recording_or_artifact_url, self_check_summary, review_status
                ) VALUES (
                    :submissionId, :qualificationId, :attemptNumber,
                    :lessonObjective, :recordingUrl, :selfCheckSummary, 'PENDING_REVIEW'
                )
                """)
                .param("submissionId", UUID.randomUUID())
                .param("qualificationId", qualificationId)
                .param("attemptNumber", nextAttempt == null ? 1 : nextAttempt)
                .param("lessonObjective", req.lessonObjective().trim())
                .param("recordingUrl", req.recordingOrArtifactUrl().trim())
                .param("selfCheckSummary", req.selfCheckSummary().trim())
                .update();

        jdbcClient.sql("""
                UPDATE teaching_qualifications
                SET status = 'PENDING_HUMAN_REVIEW',
                    updated_at = NOW()
                WHERE qualification_id = :qualificationId
                """)
                .param("qualificationId", qualificationId)
                .update();
    }

    public Optional<SubmissionContext> findSubmissionContext(UUID submissionId) {
        return jdbcClient.sql("""
                SELECT s.submission_id, s.qualification_id, s.review_status, tq.user_id AS candidate_user_id
                FROM mini_class_submissions s
                JOIN teaching_qualifications tq ON tq.qualification_id = s.qualification_id
                WHERE s.submission_id = :submissionId
                """)
                .param("submissionId", submissionId)
                .query((rs, rowNum) -> new SubmissionContext(
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("qualification_id", UUID.class),
                        rs.getString("review_status"),
                        rs.getString("candidate_user_id")))
                .optional();
    }

    public void applyReviewDecision(
            UUID submissionId,
            UUID qualificationId,
            String reviewerUserId,
            String newReviewStatus,
            String newQualificationStatus,
            String reviewerComments) {

        jdbcClient.sql("""
                UPDATE mini_class_submissions
                SET review_status = :reviewStatus,
                    reviewer_user_id = :reviewerUserId,
                    reviewer_comments = :reviewerComments,
                    reviewed_at = NOW()
                WHERE submission_id = :submissionId
                """)
                .param("reviewStatus", newReviewStatus)
                .param("reviewerUserId", reviewerUserId)
                .param("reviewerComments", reviewerComments.trim())
                .param("submissionId", submissionId)
                .update();

        jdbcClient.sql("""
                UPDATE teaching_qualifications
                SET status = :qualStatus,
                    approved_at = CASE WHEN :qualStatus = 'APPROVED' THEN NOW() ELSE NULL END,
                    updated_at = NOW()
                WHERE qualification_id = :qualificationId
                """)
                .param("qualStatus", newQualificationStatus)
                .param("qualificationId", qualificationId)
                .update();
    }

    public List<PendingSubmissionResponse> findPendingSubmissions() {
        return jdbcClient.sql("""
                SELECT m.submission_id,
                       m.qualification_id,
                       tq.user_id AS candidate_user_id,
                       tq.skill_id,
                       sk.slug AS skill_slug,
                       tq.level_id,
                       sl.code AS level_code,
                       m.attempt_number,
                       m.lesson_objective,
                       m.recording_or_artifact_url,
                       m.self_check_summary,
                       m.submitted_at
                FROM mini_class_submissions m
                JOIN teaching_qualifications tq ON tq.qualification_id = m.qualification_id
                JOIN skills sk ON sk.skill_id = tq.skill_id
                JOIN skill_levels sl ON sl.level_id = tq.level_id
                WHERE m.review_status = 'PENDING_REVIEW'
                ORDER BY m.submitted_at ASC
                """)
                .query((rs, rowNum) -> new PendingSubmissionResponse(
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("qualification_id", UUID.class),
                        rs.getString("candidate_user_id"),
                        rs.getObject("skill_id", UUID.class),
                        rs.getString("skill_slug"),
                        rs.getObject("level_id", UUID.class),
                        rs.getString("level_code"),
                        rs.getInt("attempt_number"),
                        rs.getString("lesson_objective"),
                        rs.getString("recording_or_artifact_url"),
                        rs.getString("self_check_summary"),
                        rs.getObject("submitted_at", OffsetDateTime.class)))
                .list();
    }

    private static String baseSelectQuery() {
        return """
                SELECT tq.qualification_id,
                       tq.user_id,
                       tq.skill_id,
                       tq.level_id,
                       tq.status,
                       tq.methodology_acknowledged,
                       tq.practical_test_passed,
                       tq.approved_at,
                       (SELECT COUNT(*) FROM mini_class_submissions m WHERE m.qualification_id = tq.qualification_id) AS attempt_count,
                       (SELECT m.submission_id
                        FROM mini_class_submissions m
                        WHERE m.qualification_id = tq.qualification_id
                        ORDER BY m.attempt_number DESC
                        LIMIT 1) AS latest_submission_id,
                       (SELECT m.reviewer_comments
                        FROM mini_class_submissions m
                        WHERE m.qualification_id = tq.qualification_id
                        ORDER BY m.attempt_number DESC
                        LIMIT 1) AS latest_review_comments
                FROM teaching_qualifications tq
                """;
    }

    private static QualificationResponse mapQualification(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new QualificationResponse(
                rs.getObject("qualification_id", UUID.class),
                rs.getString("user_id"),
                rs.getObject("skill_id", UUID.class),
                rs.getObject("level_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("methodology_acknowledged"),
                rs.getBoolean("practical_test_passed"),
                rs.getInt("attempt_count"),
                rs.getObject("latest_submission_id", UUID.class),
                rs.getString("latest_review_comments"),
                rs.getObject("approved_at", OffsetDateTime.class));
    }

    public record SubmissionContext(
            UUID submissionId,
            UUID qualificationId,
            String reviewStatus,
            String candidateUserId) {
    }
}

