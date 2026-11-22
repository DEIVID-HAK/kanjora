package com.kanjora.domain.matching;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingRepository {

    private final JdbcClient jdbcClient;

    public MatchingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UserMatchingContext> findUserContext(String userId) {
        Optional<UserHeader> headerOpt = jdbcClient.sql("""
                SELECT user_id, display_name, timezone
                FROM user_profiles
                WHERE user_id = :userId AND is_active = TRUE
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new UserHeader(
                        rs.getString("user_id"),
                        rs.getString("display_name"),
                        rs.getString("timezone")))
                .optional();

        if (headerOpt.isEmpty()) {
            return Optional.empty();
        }
        UserHeader header = headerOpt.get();

        List<AvailabilityWindow> slots = findAvailabilitySlots(userId);
        boolean hasApprovedQualification = Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM teaching_qualifications
                    WHERE user_id = :userId AND status = 'APPROVED'
                )
                """)
                .param("userId", userId)
                .query(Boolean.class)
                .single());

        boolean hasLearningGoal = Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM user_learning_goals
                    WHERE user_id = :userId
                )
                """)
                .param("userId", userId)
                .query(Boolean.class)
                .single());

        return Optional.of(new UserMatchingContext(
                header.userId(),
                header.displayName(),
                header.timezone(),
                hasApprovedQualification,
                hasLearningGoal,
                slots));
    }

    public List<AvailabilityWindow> findAvailabilitySlots(String userId) {
        return jdbcClient.sql("""
                SELECT day_of_week, start_time_local, end_time_local
                FROM user_availability_slots
                WHERE user_id = :userId
                ORDER BY day_of_week ASC, start_time_local ASC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new AvailabilityWindow(
                        rs.getInt("day_of_week"),
                        rs.getObject("start_time_local", LocalTime.class),
                        rs.getObject("end_time_local", LocalTime.class)))
                .list();
    }

    public List<ReciprocalPairRow> findReciprocalPairs(String myUserId) {
        return jdbcClient.sql("""
                SELECT
                    cand_prof.user_id         AS candidate_user_id,
                    cand_prof.display_name    AS candidate_display_name,
                    cand_prof.timezone        AS candidate_timezone,
                    my_tq.qualification_id    AS my_qualification_id,
                    my_tq.skill_id            AS my_teaches_skill_id,
                    my_teach_skill.slug       AS my_teaches_skill_slug,
                    my_tq.level_id            AS my_teaches_level_id,
                    cand_tq.qualification_id  AS cand_qualification_id,
                    cand_tq.skill_id          AS cand_teaches_skill_id,
                    cand_teach_skill.slug     AS cand_teaches_skill_slug,
                    cand_tq.level_id          AS cand_teaches_level_id,
                    cand_goal.target_level_id AS cand_learns_target_level_id
                FROM teaching_qualifications my_tq
                JOIN skill_levels my_teach_lvl
                  ON my_teach_lvl.level_id = my_tq.level_id
                JOIN skills my_teach_skill
                  ON my_teach_skill.skill_id = my_tq.skill_id AND my_teach_skill.is_active = TRUE
                JOIN user_learning_goals my_goal
                  ON my_goal.user_id = my_tq.user_id
                JOIN skill_levels my_goal_lvl
                  ON my_goal_lvl.level_id = my_goal.target_level_id
                JOIN teaching_qualifications cand_tq
                  ON cand_tq.skill_id = my_goal.skill_id
                 AND cand_tq.status = 'APPROVED'
                 AND cand_tq.user_id <> my_tq.user_id
                JOIN skill_levels cand_teach_lvl
                  ON cand_teach_lvl.level_id = cand_tq.level_id
                 AND cand_teach_lvl.rank_order >= my_goal_lvl.rank_order
                JOIN skills cand_teach_skill
                  ON cand_teach_skill.skill_id = cand_tq.skill_id AND cand_teach_skill.is_active = TRUE
                JOIN user_learning_goals cand_goal
                  ON cand_goal.user_id = cand_tq.user_id
                 AND cand_goal.skill_id = my_tq.skill_id
                JOIN skill_levels cand_goal_lvl
                  ON cand_goal_lvl.level_id = cand_goal.target_level_id
                 AND my_teach_lvl.rank_order >= cand_goal_lvl.rank_order
                JOIN user_profiles cand_prof
                  ON cand_prof.user_id = cand_tq.user_id
                 AND cand_prof.is_active = TRUE
                WHERE my_tq.user_id = :myUserId
                  AND my_tq.status = 'APPROVED'
                """)
                .param("myUserId", myUserId)
                .query((rs, rowNum) -> new ReciprocalPairRow(
                        rs.getString("candidate_user_id"),
                        rs.getString("candidate_display_name"),
                        rs.getString("candidate_timezone"),
                        rs.getObject("my_qualification_id", UUID.class),
                        rs.getObject("my_teaches_skill_id", UUID.class),
                        rs.getString("my_teaches_skill_slug"),
                        rs.getObject("my_teaches_level_id", UUID.class),
                        rs.getObject("cand_qualification_id", UUID.class),
                        rs.getObject("cand_teaches_skill_id", UUID.class),
                        rs.getString("cand_teaches_skill_slug"),
                        rs.getObject("cand_teaches_level_id", UUID.class),
                        rs.getObject("cand_learns_target_level_id", UUID.class)))
                .list();
    }

    public Optional<ReciprocalPairRow> findSpecificReciprocalPair(
            String proposerUserId,
            String recipientUserId,
            UUID proposerTeachesSkillId,
            UUID proposerTeachesLevelId,
            UUID recipientTeachesSkillId,
            UUID recipientTeachesLevelId) {

        return jdbcClient.sql("""
                SELECT
                    cand_prof.user_id         AS candidate_user_id,
                    cand_prof.display_name    AS candidate_display_name,
                    cand_prof.timezone        AS candidate_timezone,
                    my_tq.qualification_id    AS my_qualification_id,
                    my_tq.skill_id            AS my_teaches_skill_id,
                    my_teach_skill.slug       AS my_teaches_skill_slug,
                    my_tq.level_id            AS my_teaches_level_id,
                    cand_tq.qualification_id  AS cand_qualification_id,
                    cand_tq.skill_id          AS cand_teaches_skill_id,
                    cand_teach_skill.slug     AS cand_teaches_skill_slug,
                    cand_tq.level_id          AS cand_teaches_level_id,
                    cand_goal.target_level_id AS cand_learns_target_level_id
                FROM teaching_qualifications my_tq
                JOIN skill_levels my_teach_lvl ON my_teach_lvl.level_id = my_tq.level_id
                JOIN skills my_teach_skill ON my_teach_skill.skill_id = my_tq.skill_id AND my_teach_skill.is_active = TRUE
                JOIN user_learning_goals my_goal
                  ON my_goal.user_id = my_tq.user_id AND my_goal.skill_id = :recipientTeachesSkillId
                JOIN skill_levels my_goal_lvl ON my_goal_lvl.level_id = my_goal.target_level_id
                JOIN teaching_qualifications cand_tq
                  ON cand_tq.user_id = :recipientUserId
                 AND cand_tq.skill_id = :recipientTeachesSkillId
                 AND cand_tq.level_id = :recipientTeachesLevelId
                 AND cand_tq.status = 'APPROVED'
                JOIN skill_levels cand_teach_lvl
                  ON cand_teach_lvl.level_id = cand_tq.level_id
                 AND cand_teach_lvl.rank_order >= my_goal_lvl.rank_order
                JOIN skills cand_teach_skill ON cand_teach_skill.skill_id = cand_tq.skill_id AND cand_teach_skill.is_active = TRUE
                JOIN user_learning_goals cand_goal
                  ON cand_goal.user_id = cand_tq.user_id AND cand_goal.skill_id = :proposerTeachesSkillId
                JOIN skill_levels cand_goal_lvl
                  ON cand_goal_lvl.level_id = cand_goal.target_level_id
                 AND my_teach_lvl.rank_order >= cand_goal_lvl.rank_order
                JOIN user_profiles cand_prof
                  ON cand_prof.user_id = cand_tq.user_id AND cand_prof.is_active = TRUE
                WHERE my_tq.user_id = :proposerUserId
                  AND my_tq.skill_id = :proposerTeachesSkillId
                  AND my_tq.level_id = :proposerTeachesLevelId
                  AND my_tq.status = 'APPROVED'
                """)
                .param("proposerUserId", proposerUserId)
                .param("recipientUserId", recipientUserId)
                .param("proposerTeachesSkillId", proposerTeachesSkillId)
                .param("proposerTeachesLevelId", proposerTeachesLevelId)
                .param("recipientTeachesSkillId", recipientTeachesSkillId)
                .param("recipientTeachesLevelId", recipientTeachesLevelId)
                .query((rs, rowNum) -> new ReciprocalPairRow(
                        rs.getString("candidate_user_id"),
                        rs.getString("candidate_display_name"),
                        rs.getString("candidate_timezone"),
                        rs.getObject("my_qualification_id", UUID.class),
                        rs.getObject("my_teaches_skill_id", UUID.class),
                        rs.getString("my_teaches_skill_slug"),
                        rs.getObject("my_teaches_level_id", UUID.class),
                        rs.getObject("cand_qualification_id", UUID.class),
                        rs.getObject("cand_teaches_skill_id", UUID.class),
                        rs.getString("cand_teaches_skill_slug"),
                        rs.getObject("cand_teaches_level_id", UUID.class),
                        rs.getObject("cand_learns_target_level_id", UUID.class)))
                .optional();
    }

    public int countSharedTopicsBetweenPair(
            String userA,
            UUID aTeachesSkillId,
            UUID aTeachesLevelId,
            String userB,
            UUID bTeachesSkillId,
            UUID bTeachesLevelId) {

        Set<UUID> aGoalTopics = findGoalTopics(userA, bTeachesSkillId);
        Set<UUID> bOfferTopics = findOfferTopics(userB, bTeachesSkillId, bTeachesLevelId);
        Set<UUID> bGoalTopics = findGoalTopics(userB, aTeachesSkillId);
        Set<UUID> aOfferTopics = findOfferTopics(userA, aTeachesSkillId, aTeachesLevelId);

        int count = 0;
        for (UUID id : aGoalTopics) {
            if (bOfferTopics.contains(id)) {
                count++;
            }
        }
        for (UUID id : bGoalTopics) {
            if (aOfferTopics.contains(id)) {
                count++;
            }
        }
        return count;
    }

    private Set<UUID> findGoalTopics(String userId, UUID skillId) {
        return new HashSet<>(jdbcClient.sql("""
                SELECT gt.topic_id
                FROM user_learning_goal_topics gt
                JOIN user_learning_goals g ON g.goal_id = gt.goal_id
                WHERE g.user_id = :userId AND g.skill_id = :skillId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .query(UUID.class)
                .list());
    }

    private Set<UUID> findOfferTopics(String userId, UUID skillId, UUID levelId) {
        return new HashSet<>(jdbcClient.sql("""
                SELECT ot.topic_id
                FROM user_teaching_offer_topics ot
                JOIN user_teaching_offers o ON o.offer_id = ot.offer_id
                WHERE o.user_id = :userId AND o.skill_id = :skillId AND o.level_id = :levelId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .param("levelId", levelId)
                .query(UUID.class)
                .list());
    }

    public Map<String, List<AvailabilityWindow>> findAvailabilityByUsers(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AvailabilityWindow>> result = new HashMap<>();
        jdbcClient.sql("""
                SELECT user_id, day_of_week, start_time_local, end_time_local
                FROM user_availability_slots
                WHERE user_id IN (:userIds)
                ORDER BY user_id, day_of_week, start_time_local
                """)
                .param("userIds", userIds)
                .query((rs, rowNum) -> {
                    String uid = rs.getString("user_id");
                    AvailabilityWindow win = new AvailabilityWindow(
                            rs.getInt("day_of_week"),
                            rs.getObject("start_time_local", LocalTime.class),
                            rs.getObject("end_time_local", LocalTime.class));
                    result.computeIfAbsent(uid, k -> new java.util.ArrayList<>()).add(win);
                    return win;
                })
                .list();
        return result;
    }

    private record UserHeader(String userId, String displayName, String timezone) {
    }

    public record AvailabilityWindow(int dayOfWeek, LocalTime startTimeLocal, LocalTime endTimeLocal) {
    }

    public record UserMatchingContext(
            String userId,
            String displayName,
            String timezone,
            boolean hasApprovedQualification,
            boolean hasLearningGoal,
            List<AvailabilityWindow> availabilitySlots) {
    }

    public record ReciprocalPairRow(
            String candidateUserId,
            String candidateDisplayName,
            String candidateTimezone,
            UUID myQualificationId,
            UUID myTeachesSkillId,
            String myTeachesSkillSlug,
            UUID myTeachesLevelId,
            UUID candQualificationId,
            UUID candTeachesSkillId,
            String candTeachesSkillSlug,
            UUID candTeachesLevelId,
            UUID candLearnsTargetLevelId) {
    }
}

