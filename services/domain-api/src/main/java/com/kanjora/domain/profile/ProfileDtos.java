package com.kanjora.domain.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record AvailabilitySlotDto(
            @Min(1) @Max(7) int dayOfWeek,
            @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Formato requerido HH:mm")
            String startTimeLocal,
            @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Formato requerido HH:mm")
            String endTimeLocal) {
    }

    public record LearningGoalInputDto(
            @NotNull UUID skillId,
            @NotNull UUID targetLevelId,
            List<UUID> topicIds) {
    }

    public record TeachingOfferInputDto(
            @NotNull UUID skillId,
            @NotNull UUID levelId,
            List<UUID> topicIds) {
    }

    public record UserProfileUpsertRequest(
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @Size(max = 500) String bio,
            @NotBlank @Size(max = 64) String timezone,
            @NotNull @Valid List<AvailabilitySlotDto> availabilitySlots,
            @NotNull @Valid List<LearningGoalInputDto> learningGoals,
            @NotNull @Valid List<TeachingOfferInputDto> teachingOffers) {
    }

    public record TeachingOfferResponseDto(
            UUID skillId,
            UUID levelId,
            String qualificationStatus,
            List<UUID> topicIds) {
    }

    public record UserProfileResponse(
            String userId,
            String displayName,
            String bio,
            String timezone,
            List<AvailabilitySlotDto> availabilitySlots,
            List<LearningGoalInputDto> learningGoals,
            List<TeachingOfferResponseDto> teachingOffers,
            OffsetDateTime updatedAt) {
    }
}

