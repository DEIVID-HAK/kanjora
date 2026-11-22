package com.kanjora.domain.confirmation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class BilateralConfirmationDtos {

    private BilateralConfirmationDtos() {
    }

    public enum OutcomeDeclaration {
        COMPLETED,
        INCOMPLETE_TECHNICAL_ISSUE,
        PARTNER_NO_SHOW,
        PARTNER_ABANDONED,
        METHODOLOGY_NOT_FOLLOWED
    }

    public record BilateralConfirmationRequest(
            @NotNull UUID sessionId,
            @NotNull OutcomeDeclaration outcomeDeclaration,
            boolean bothBlocksExecuted,
            @Size(max = 1000) String learnedSkillProgressNote,
            @Size(max = 200) String incidentReason,
            @Size(max = 2000) String incidentDetails,
            boolean wouldRepeatWithPartner) {
    }

    public record BilateralConfirmationResponse(
            UUID proposalId,
            UUID sessionId,
            OffsetDateTime myConfirmationRecordedAt,
            boolean partnerHasConfirmed,
            String exchangeResolutionStatus,
            boolean creditEligibilityFactRecorded) {
    }

    public record ExchangeHistoryItemResponse(
            UUID proposalId,
            UUID sessionId,
            String partnerUserId,
            String partnerDisplayName,
            String myTeachingSkillSlug,
            String partnerTeachingSkillSlug,
            String proposalStatus,
            String resolutionStatus,
            String myOutcomeDeclaration,
            String myProgressNote,
            Boolean canRepeatWithPartner,
            OffsetDateTime createdAt,
            OffsetDateTime resolvedAt) {
    }

    public record PilotMetricsResponse(
            long profilesCompleted,
            long qualificationsSubmitted,
            long qualificationsApproved,
            long proposalsCreated,
            long proposalsAccepted,
            long exchangesConfirmed,
            long exchangesPendingReview,
            long cancelledNoPenaltyCount) {
    }
}

