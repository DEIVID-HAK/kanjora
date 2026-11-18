package com.kanjora.domain.proposal;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ProposalDtos {

    private ProposalDtos() {
    }

    public enum ProposalDecision {
        ACCEPT,
        REJECT
    }

    public record CreateProposalRequest(
            @NotBlank String recipientUserId,
            @NotNull UUID proposerTeachesSkillId,
            @NotNull UUID proposerTeachesLevelId,
            @Size(max = 64) String proposerTopicCode,
            @NotNull UUID recipientTeachesSkillId,
            @NotNull UUID recipientTeachesLevelId,
            @Size(max = 64) String recipientTopicCode) {
    }

    public record RespondProposalRequest(
            @NotNull ProposalDecision decision,
            @Size(max = 500) String reason) {
    }

    public record ExchangeProposalResponse(
            UUID proposalId,
            String status,
            String proposerUserId,
            String recipientUserId,
            OffsetDateTime createdAt,
            OffsetDateTime respondedAt) {
    }

    public record TeachesValidationInfo(
            UUID skillId,
            String skillSlug,
            UUID levelId,
            String topicCode,
            UUID teachingQualificationId,
            @JsonProperty("isQualified") boolean isQualified) {
    }

    public record LearnsValidationInfo(
            UUID skillId,
            UUID levelId) {
    }

    public record ParticipantValidationInfo(
            String userId,
            TeachesValidationInfo teaches,
            LearnsValidationInfo learns) {
    }

    public record ProposalValidationResponse(
            UUID proposalId,
            String status,
            boolean validForScheduling,
            String invalidationReason,
            List<ParticipantValidationInfo> participants,
            List<UUID> recommendedGuideIds) {
    }
}

