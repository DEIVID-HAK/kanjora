package com.kanjora.domain.catalog;

import com.kanjora.domain.catalog.CatalogDtos.CatalogSkillResponse;
import com.kanjora.domain.catalog.CatalogDtos.CategoryDto;
import com.kanjora.domain.catalog.CatalogDtos.LevelDto;
import com.kanjora.domain.catalog.CatalogDtos.TopicDto;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcClient jdbcClient;

    public CatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CatalogSkillResponse> findActiveCatalog() {
        List<SkillRow> skills = jdbcClient.sql("""
                SELECT s.skill_id, s.slug AS skill_slug, s.name AS skill_name,
                       c.category_id, c.slug AS category_slug, c.name AS category_name
                FROM skills s
                JOIN skill_categories c ON c.category_id = s.category_id
                WHERE s.is_active = TRUE AND c.is_active = TRUE
                ORDER BY c.name ASC, s.name ASC
                """)
                .query((rs, rowNum) -> new SkillRow(
                        rs.getObject("skill_id", UUID.class),
                        rs.getString("skill_slug"),
                        rs.getString("skill_name"),
                        new CategoryDto(
                                rs.getObject("category_id", UUID.class),
                                rs.getString("category_slug"),
                                rs.getString("category_name"))))
                .list();

        if (skills.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<LevelDto>> levelsBySkill = jdbcClient.sql("""
                SELECT skill_id, level_id, code, name, rank_order
                FROM skill_levels
                WHERE is_active = TRUE
                ORDER BY rank_order ASC
                """)
                .query((rs, rowNum) -> new LevelRow(
                        rs.getObject("skill_id", UUID.class),
                        new LevelDto(
                                rs.getObject("level_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getInt("rank_order"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        LevelRow::skillId,
                        Collectors.mapping(LevelRow::level, Collectors.toList())));

        Map<UUID, List<TopicDto>> topicsBySkill = jdbcClient.sql("""
                SELECT skill_id, topic_id, code, name
                FROM skill_topics
                WHERE is_active = TRUE
                ORDER BY name ASC
                """)
                .query((rs, rowNum) -> new TopicRow(
                        rs.getObject("skill_id", UUID.class),
                        new TopicDto(
                                rs.getObject("topic_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        TopicRow::skillId,
                        Collectors.mapping(TopicRow::topic, Collectors.toList())));

        return skills.stream()
                .map(s -> new CatalogSkillResponse(
                        s.skillId(),
                        s.slug(),
                        s.name(),
                        s.category(),
                        levelsBySkill.getOrDefault(s.skillId(), List.of()),
                        topicsBySkill.getOrDefault(s.skillId(), List.of())))
                .toList();
    }

    public boolean skillAndLevelExist(UUID skillId, UUID levelId) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM skill_levels sl
                JOIN skills s ON s.skill_id = sl.skill_id
                JOIN skill_categories c ON c.category_id = s.category_id
                WHERE sl.skill_id = :skillId
                  AND sl.level_id = :levelId
                  AND sl.is_active = TRUE
                  AND s.is_active = TRUE
                  AND c.is_active = TRUE
                """)
                .param("skillId", skillId)
                .param("levelId", levelId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public boolean allTopicsBelongToSkill(UUID skillId, Collection<UUID> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return true;
        }
        Set<UUID> uniqueTopicIds = new HashSet<>(topicIds);
        Integer count = jdbcClient.sql("""
                SELECT COUNT(DISTINCT topic_id)
                FROM skill_topics
                WHERE skill_id = :skillId
                  AND is_active = TRUE
                  AND topic_id IN (:topicIds)
                """)
                .param("skillId", skillId)
                .param("topicIds", uniqueTopicIds)
                .query(Integer.class)
                .single();
        return count != null && count == uniqueTopicIds.size();
    }

    private record SkillRow(UUID skillId, String slug, String name, CategoryDto category) {
    }

    private record LevelRow(UUID skillId, LevelDto level) {
    }

    private record TopicRow(UUID skillId, TopicDto topic) {
    }
}

