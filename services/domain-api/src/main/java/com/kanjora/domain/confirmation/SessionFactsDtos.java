package com.kanjora.domain.confirmation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SessionFactsDtos {

    private SessionFactsDtos() {
    }

    public enum ObservedPresence {
        PRESENT_FULL,
        PRESENT_WITH_RECONNECT,
        ABSENT,
        DROPPED_MID_CALL
    }

    public enum TerminalSessionState {
        COMPLETED_OBSERVED,
        NO_SHOW_OBSERVED,
        ABANDONED_MID_CALL,
        TECHNICAL_FAILURE_OBSERVED,
        CANCELLED_BEFORE_START
    }

    public record ParticipantAttendanceFact(
            @NotBlank String userId,
            OffsetDateTime joinedAt,
            OffsetDateTime leftAt,
            @Min(0) int connectedSeconds,
            @Min(0) int disconnectionCount,
            @NotNull ObservedPresence observedPresence) {
    }

    public record SessionFactsReportRequest(
            @NotBlank @Size(min = 8, max = 128) String factReportId,
            @NotNull UUID sessionId,
            @NotNull UUID proposalId,
            @NotNull TerminalSessionState terminalSessionState,
            @NotNull OffsetDateTime occurredAt,
            @NotNull @Size(min = 2, max = 2) @Valid List<ParticipantAttendanceFact> participantsAttendance,
            boolean technicalIncidentObserved,
            @Size(max = 64) String incidentCode) {
    }

    public record SessionFactsReportResponse(
            String factReportId,
            UUID proposalId,
            String exchangeStatus,
            boolean idempotentReplay) {
    }
}

