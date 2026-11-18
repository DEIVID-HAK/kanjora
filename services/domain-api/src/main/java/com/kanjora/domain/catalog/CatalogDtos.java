package com.kanjora.domain.catalog;

import java.util.List;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryDto(
            UUID categoryId,
            String slug,
            String name) {
    }

    public record LevelDto(
            UUID levelId,
            String code,
            String name,
            int rankOrder) {
    }

    public record TopicDto(
            UUID topicId,
            String code,
            String name) {
    }

    public record CatalogSkillResponse(
            UUID skillId,
            String slug,
            String name,
            CategoryDto category,
            List<LevelDto> levels,
            List<TopicDto> topics) {
    }
}

