package com.kanjora.domain.matching;

import java.util.List;
import java.util.UUID;

public final class MatchingDtos {

    private MatchingDtos() {
    }

    public record MatchExplanation(
            int commonWeeklyMinutes,
            List<String> overlappingWindows,
            int sharedTopicsCount,
            String summaryText) {
    }

    public record TeachesSummary(
            UUID skillId,
            String skillSlug,
            UUID levelId,
            UUID qualificationId) {
    }

    public record LearnsSummary(
            UUID skillId,
            String skillSlug,
            UUID levelId) {
    }

    public record MatchCandidateResponse(
            String candidateUserId,
            String displayName,
            TeachesSummary candidateTeaches,
            LearnsSummary candidateLearns,
            double score,
            MatchExplanation explanation) {
    }

    public record MatchSearchResponse(
            boolean searchActive,
            boolean hasValidMatches,
            String emptyStateMessage,
            List<MatchCandidateResponse> candidates) {
    }
}

