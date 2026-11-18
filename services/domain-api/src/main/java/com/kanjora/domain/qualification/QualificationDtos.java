package com.kanjora.domain.qualification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class QualificationDtos {

    private QualificationDtos() {
    }

    public enum ReviewDecision {
        APPROVE,
        REJECT
    }

    public record CreateQualificationRequest(
            @NotNull UUID skillId,
            @NotNull UUID levelId,
            boolean methodologyAcknowledged,
            boolean practicalTestPassed,
            @Size(max = 255) String practicalTestReference) {
    }

    public record QualificationResponse(
            UUID qualificationId,
            String userId,
            UUID skillId,
            UUID levelId,
            String status,
            boolean methodologyAcknowledged,
            boolean practicalTestPassed,
            int attemptCount,
            UUID latestSubmissionId,
            String latestReviewComments,
            OffsetDateTime approvedAt) {
    }

    public record MiniClassSubmissionRequest(
            @NotBlank @Size(min = 10, max = 500) String lessonObjective,
            @NotBlank @Size(max = 1000) String recordingOrArtifactUrl,
            @NotBlank @Size(min = 10, max = 1000) String selfCheckSummary) {
    }

    public record MiniClassReviewRequest(
            @NotNull ReviewDecision decision,
            @NotBlank @Size(min = 10, max = 2000) String reviewerComments) {
    }
}

