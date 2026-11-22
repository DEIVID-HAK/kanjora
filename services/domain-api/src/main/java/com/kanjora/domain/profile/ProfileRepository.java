package com.kanjora.domain.profile;

import com.kanjora.domain.profile.ProfileDtos.AvailabilitySlotDto;
import com.kanjora.domain.profile.ProfileDtos.LearningGoalInputDto;
import com.kanjora.domain.profile.ProfileDtos.TeachingOfferInputDto;
import com.kanjora.domain.profile.ProfileDtos.TeachingOfferResponseDto;
import com.kanjora.domain.profile.ProfileDtos.UserProfileResponse;
import com.kanjora.domain.profile.ProfileDtos.UserProfileUpsertRequest;
import java.sql.Types;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbcClient;

    public ProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UserProfileResponse> findProfile(String userId) {
        Optional<ProfileHeader> headerOpt = jdbcClient.sql("""
                SELECT user_id, display_name, bio, timezone, updated_at
                FROM user_profiles
                WHERE user_id = :userId AND is_active = TRUE
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new ProfileHeader(
                        rs.getString("user_id"),
                        rs.getString("display_name"),
                        rs.getString("bio"),
                        rs.getString("timezone"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();

        if (headerOpt.isEmpty()) {
            return Optional.empty();
        }
        ProfileHeader header = headerOpt.get();

        List<AvailabilitySlotDto> slots = jdbcClient.sql("""
                SELECT day_of_week, start_time_local, end_time_local
                FROM user_availability_slots
                WHERE user_id = :userId
                ORDER BY day_of_week ASC, start_time_local ASC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new AvailabilitySlotDto(
                        rs.getInt("day_of_week"),
                        rs.getObject("start_time_local", LocalTime.class).format(HH_MM),
                        rs.getObject("end_time_local", LocalTime.class).format(HH_MM)))
                .list();

        List<LearningGoalRow> goalRows = jdbcClient.sql("""
                SELECT goal_id, skill_id, target_level_id
                FROM user_learning_goals
                WHERE user_id = :userId
                ORDER BY created_at ASC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new LearningGoalRow(
                        rs.getObject("goal_id", UUID.class),
                        rs.getObject("skill_id", UUID.class),
                        rs.getObject("target_level_id", UUID.class)))
                .list();

        Map<UUID, List<UUID>> goalTopics = goalRows.isEmpty()
                ? Map.of()
                : jdbcClient.sql("""
                        SELECT gt.goal_id, gt.topic_id
                        FROM user_learning_goal_topics gt
                        JOIN user_learning_goals g ON g.goal_id = gt.goal_id
                        WHERE g.user_id = :userId
                        """)
                        .param("userId", userId)
                        .query((rs, rowNum) -> new IdPair(
                                rs.getObject("goal_id", UUID.class),
                                rs.getObject("topic_id", UUID.class)))
                        .list()
                        .stream()
                        .collect(Collectors.groupingBy(
                                IdPair::parentId,
                                Collectors.mapping(IdPair::topicId, Collectors.toList())));

        List<LearningGoalInputDto> learningGoals = goalRows.stream()
                .map(g -> new LearningGoalInputDto(
                        g.skillId(),
                        g.targetLevelId(),
                        goalTopics.getOrDefault(g.goalId(), List.of())))
                .toList();

        List<TeachingOfferRow> offerRows = jdbcClient.sql("""
                SELECT uto.offer_id, uto.skill_id, uto.level_id,
                       COALESCE(tq.status, 'NOT_STARTED') AS qualification_status
                FROM user_teaching_offers uto
                LEFT JOIN teaching_qualifications tq
                       ON tq.user_id = uto.user_id
                      AND tq.skill_id = uto.skill_id
                      AND tq.level_id = uto.level_id
                WHERE uto.user_id = :userId
                ORDER BY uto.created_at ASC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new TeachingOfferRow(
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("skill_id", UUID.class),
                        rs.getObject("level_id", UUID.class),
                        rs.getString("qualification_status")))
                .list();

        Map<UUID, List<UUID>> offerTopics = offerRows.isEmpty()
                ? Map.of()
                : jdbcClient.sql("""
                        SELECT ot.offer_id, ot.topic_id
                        FROM user_teaching_offer_topics ot
                        JOIN user_teaching_offers o ON o.offer_id = ot.offer_id
                        WHERE o.user_id = :userId
                        """)
                        .param("userId", userId)
                        .query((rs, rowNum) -> new IdPair(
                                rs.getObject("offer_id", UUID.class),
                                rs.getObject("topic_id", UUID.class)))
                        .list()
                        .stream()
                        .collect(Collectors.groupingBy(
                                IdPair::parentId,
                                Collectors.mapping(IdPair::topicId, Collectors.toList())));

        List<TeachingOfferResponseDto> teachingOffers = offerRows.stream()
                .map(o -> new TeachingOfferResponseDto(
                        o.skillId(),
                        o.levelId(),
                        o.qualificationStatus(),
                        offerTopics.getOrDefault(o.offerId(), List.of())))
                .toList();

        return Optional.of(new UserProfileResponse(
                header.userId(),
                header.displayName(),
                header.bio(),
                header.timezone(),
                slots,
                learningGoals,
                teachingOffers,
                header.updatedAt()));
    }

    public void upsertProfile(String userId, UserProfileUpsertRequest req) {
        jdbcClient.sql("""
                INSERT INTO user_profiles (user_id, display_name, bio, timezone, updated_at)
                VALUES (:userId, :displayName, :bio, :timezone, NOW())
                ON CONFLICT (user_id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    bio = EXCLUDED.bio,
                    timezone = EXCLUDED.timezone,
                    is_active = TRUE,
                    updated_at = NOW()
                """)
                .param("userId", userId)
                .param("displayName", req.displayName().trim())
                .param("bio", req.bio(), Types.VARCHAR)
                .param("timezone", req.timezone().trim())
                .update();

        jdbcClient.sql("DELETE FROM user_availability_slots WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        for (AvailabilitySlotDto slot : req.availabilitySlots()) {
            jdbcClient.sql("""
                    INSERT INTO user_availability_slots (slot_id, user_id, day_of_week, start_time_local, end_time_local)
                    VALUES (:slotId, :userId, :dayOfWeek, :startTime, :endTime)
                    """)
                    .param("slotId", UUID.randomUUID())
                    .param("userId", userId)
                    .param("dayOfWeek", slot.dayOfWeek())
                    .param("startTime", LocalTime.parse(slot.startTimeLocal()))
                    .param("endTime", LocalTime.parse(slot.endTimeLocal()))
                    .update();
        }

        jdbcClient.sql("DELETE FROM user_learning_goals WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        for (LearningGoalInputDto goal : req.learningGoals()) {
            UUID goalId = UUID.randomUUID();
            jdbcClient.sql("""
                    INSERT INTO user_learning_goals (goal_id, user_id, skill_id, target_level_id)
                    VALUES (:goalId, :userId, :skillId, :targetLevelId)
                    """)
                    .param("goalId", goalId)
                    .param("userId", userId)
                    .param("skillId", goal.skillId())
                    .param("targetLevelId", goal.targetLevelId())
                    .update();

            if (goal.topicIds() != null) {
                for (UUID topicId : goal.topicIds().stream().distinct().toList()) {
                    jdbcClient.sql("""
                            INSERT INTO user_learning_goal_topics (goal_id, topic_id, skill_id)
                            VALUES (:goalId, :topicId, :skillId)
                            """)
                            .param("goalId", goalId)
                            .param("topicId", topicId)
                            .param("skillId", goal.skillId())
                            .update();
                }
            }
        }

        jdbcClient.sql("DELETE FROM user_teaching_offers WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        for (TeachingOfferInputDto offer : req.teachingOffers()) {
            UUID offerId = UUID.randomUUID();
            jdbcClient.sql("""
                    INSERT INTO user_teaching_offers (offer_id, user_id, skill_id, level_id)
                    VALUES (:offerId, :userId, :skillId, :levelId)
                    """)
                    .param("offerId", offerId)
                    .param("userId", userId)
                    .param("skillId", offer.skillId())
                    .param("levelId", offer.levelId())
                    .update();

            if (offer.topicIds() != null) {
                for (UUID topicId : offer.topicIds().stream().distinct().toList()) {
                    jdbcClient.sql("""
                            INSERT INTO user_teaching_offer_topics (offer_id, topic_id, skill_id)
                            VALUES (:offerId, :topicId, :skillId)
                            """)
                            .param("offerId", offerId)
                            .param("topicId", topicId)
                            .param("skillId", offer.skillId())
                            .update();
                }
            }
        }
    }

    private record ProfileHeader(
            String userId,
            String displayName,
            String bio,
            String timezone,
            OffsetDateTime updatedAt) {
    }

    private record LearningGoalRow(UUID goalId, UUID skillId, UUID targetLevelId) {
    }

    private record TeachingOfferRow(UUID offerId, UUID skillId, UUID levelId, String qualificationStatus) {
    }

    private record IdPair(UUID parentId, UUID topicId) {
    }
}
