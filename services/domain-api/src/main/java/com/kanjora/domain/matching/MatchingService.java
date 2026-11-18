package com.kanjora.domain.matching;

import com.kanjora.domain.matching.MatchingDtos.LearnsSummary;
import com.kanjora.domain.matching.MatchingDtos.MatchCandidateResponse;
import com.kanjora.domain.matching.MatchingDtos.MatchExplanation;
import com.kanjora.domain.matching.MatchingDtos.MatchSearchResponse;
import com.kanjora.domain.matching.MatchingDtos.TeachesSummary;
import com.kanjora.domain.matching.MatchingRepository.AvailabilityWindow;
import com.kanjora.domain.matching.MatchingRepository.ReciprocalPairRow;
import com.kanjora.domain.matching.MatchingRepository.UserMatchingContext;
import com.kanjora.domain.shared.DomainException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {

    static final int MINIMUM_COMMON_WEEKLY_MINUTES = 30;
    private static final int MINUTES_PER_WEEK = 7 * 24 * 60;
    private static final String[] DAY_LABELS = {"LUN", "MAR", "MIE", "JUE", "VIE", "SAB", "DOM"};

    private final MatchingRepository matchingRepository;

    public MatchingService(MatchingRepository matchingRepository) {
        this.matchingRepository = matchingRepository;
    }

    public MatchSearchResponse findValidMatches(String myUserId) {
        UserMatchingContext me = matchingRepository.findUserContext(myUserId)
                .orElseThrow(() -> DomainException.conflict(
                        "MATCHING_PREREQUISITES_NOT_MET",
                        "Debes completar tu perfil antes de buscar parejas de intercambio."));

        if (!me.hasApprovedQualification() || !me.hasLearningGoal() || me.availabilitySlots().isEmpty()) {
            throw DomainException.conflict(
                    "MATCHING_PREREQUISITES_NOT_MET",
                    "Necesitas al menos una habilidad habilitada para enseñar, una meta de aprendizaje y disponibilidad horaria.");
        }

        List<ReciprocalPairRow> rawPairs = matchingRepository.findReciprocalPairs(myUserId);
        if (rawPairs.isEmpty()) {
            return emptySearchResponse();
        }

        Set<String> candidateIds = rawPairs.stream()
                .map(ReciprocalPairRow::candidateUserId)
                .collect(Collectors.toSet());
        Map<String, List<AvailabilityWindow>> candidateSlotsMap =
                matchingRepository.findAvailabilityByUsers(candidateIds);

        Map<String, MatchCandidateResponse> bestPerCandidate = new LinkedHashMap<>();
        for (ReciprocalPairRow pair : rawPairs) {
            List<AvailabilityWindow> candSlots = candidateSlotsMap.getOrDefault(pair.candidateUserId(), List.of());
            OverlapResult overlap = computeWeeklyOverlap(
                    me.timezone(),
                    me.availabilitySlots(),
                    pair.candidateTimezone(),
                    candSlots);

            if (overlap.commonWeeklyMinutes() < MINIMUM_COMMON_WEEKLY_MINUTES) {
                continue;
            }

            int sharedTopicsCount = matchingRepository.countSharedTopicsBetweenPair(
                    myUserId,
                    pair.myTeachesSkillId(),
                    pair.myTeachesLevelId(),
                    pair.candidateUserId(),
                    pair.candTeachesSkillId(),
                    pair.candTeachesLevelId());

            double score = computeScore(overlap.commonWeeklyMinutes(), sharedTopicsCount);
            MatchExplanation explanation = buildExplanation(overlap, sharedTopicsCount, pair);

            MatchCandidateResponse candidate = new MatchCandidateResponse(
                    pair.candidateUserId(),
                    pair.candidateDisplayName(),
                    new TeachesSummary(
                            pair.candTeachesSkillId(),
                            pair.candTeachesSkillSlug(),
                            pair.candTeachesLevelId(),
                            pair.candQualificationId()),
                    new LearnsSummary(
                            pair.myTeachesSkillId(),
                            pair.myTeachesSkillSlug(),
                            pair.candLearnsTargetLevelId()),
                    score,
                    explanation);

            bestPerCandidate.merge(
                    pair.candidateUserId(),
                    candidate,
                    (existing, replacement) -> replacement.score() > existing.score() ? replacement : existing);
        }

        List<MatchCandidateResponse> ranked = bestPerCandidate.values().stream()
                .sorted(Comparator.comparingDouble(MatchCandidateResponse::score).reversed()
                        .thenComparing(MatchCandidateResponse::candidateUserId))
                .toList();

        if (ranked.isEmpty()) {
            return emptySearchResponse();
        }

        return new MatchSearchResponse(true, true, null, ranked);
    }

    public PairEvaluation evaluatePairCompatibility(
            UserMatchingContext proposer,
            ReciprocalPairRow pair,
            List<AvailabilityWindow> recipientSlots) {

        OverlapResult overlap = computeWeeklyOverlap(
                proposer.timezone(),
                proposer.availabilitySlots(),
                pair.candidateTimezone(),
                recipientSlots);

        if (overlap.commonWeeklyMinutes() < MINIMUM_COMMON_WEEKLY_MINUTES) {
            return new PairEvaluation(false, 0.0, null);
        }

        int sharedTopics = matchingRepository.countSharedTopicsBetweenPair(
                proposer.userId(),
                pair.myTeachesSkillId(),
                pair.myTeachesLevelId(),
                pair.candidateUserId(),
                pair.candTeachesSkillId(),
                pair.candTeachesLevelId());

        double score = computeScore(overlap.commonWeeklyMinutes(), sharedTopics);
        MatchExplanation explanation = buildExplanation(overlap, sharedTopics, pair);
        return new PairEvaluation(true, score, explanation);
    }

    static OverlapResult computeWeeklyOverlap(
            String tzA,
            List<AvailabilityWindow> slotsA,
            String tzB,
            List<AvailabilityWindow> slotsB) {

        if (slotsA.isEmpty() || slotsB.isEmpty()) {
            return new OverlapResult(0, List.of());
        }

        List<MinuteInterval> intervalsA = toWeeklyUtcIntervals(tzA, slotsA);
        List<MinuteInterval> intervalsB = toWeeklyUtcIntervals(tzB, slotsB);

        int totalMinutes = 0;
        List<String> windows = new ArrayList<>();

        for (MinuteInterval a : intervalsA) {
            for (MinuteInterval b : intervalsB) {
                int start = Math.max(a.startMinute(), b.startMinute());
                int end = Math.min(a.endMinute(), b.endMinute());
                if (end > start) {
                    totalMinutes += (end - start);
                    windows.add(formatUtcWindow(start, end));
                }
            }
        }

        return new OverlapResult(totalMinutes, windows.stream().distinct().toList());
    }

    private static List<MinuteInterval> toWeeklyUtcIntervals(String timezone, List<AvailabilityWindow> slots) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate refMonday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime weekStartUtc = refMonday.atStartOfDay(ZoneOffset.UTC);

        List<MinuteInterval> result = new ArrayList<>();
        for (AvailabilityWindow slot : slots) {
            ZonedDateTime startZdt = refMonday.plusDays(slot.dayOfWeek() - 1L)
                    .atTime(slot.startTimeLocal())
                    .atZone(zone);
            ZonedDateTime endZdt = refMonday.plusDays(slot.dayOfWeek() - 1L)
                    .atTime(slot.endTimeLocal())
                    .atZone(zone);

            int startMin = (int) Duration.between(weekStartUtc, startZdt.withZoneSameInstant(ZoneOffset.UTC)).toMinutes();
            int endMin = (int) Duration.between(weekStartUtc, endZdt.withZoneSameInstant(ZoneOffset.UTC)).toMinutes();
            addNormalizedInterval(result, startMin, endMin);
        }
        return result;
    }

    private static void addNormalizedInterval(List<MinuteInterval> target, int startMin, int endMin) {
        int normStart = Math.floorMod(startMin, MINUTES_PER_WEEK);
        int duration = endMin - startMin;
        int normEnd = normStart + duration;
        if (normEnd <= MINUTES_PER_WEEK) {
            target.add(new MinuteInterval(normStart, normEnd));
        } else {
            target.add(new MinuteInterval(normStart, MINUTES_PER_WEEK));
            target.add(new MinuteInterval(0, normEnd - MINUTES_PER_WEEK));
        }
    }

    private static String formatUtcWindow(int startMin, int endMin) {
        int dayIdx = Math.min(6, startMin / 1440);
        int startHour = (startMin % 1440) / 60;
        int startMinute = startMin % 60;
        int endHour = ((endMin - 1) % 1440) / 60;
        int endMinute = ((endMin - 1) % 60) + 1;
        if (endMinute == 60) {
            endHour++;
            endMinute = 0;
        }
        return String.format("%s %02d:%02d-%02d:%02d UTC",
                DAY_LABELS[dayIdx], startHour, startMinute, endHour, endMinute);
    }

    private static double computeScore(int commonWeeklyMinutes, int sharedTopicsCount) {
        return commonWeeklyMinutes + (sharedTopicsCount * 60.0);
    }

    private static MatchExplanation buildExplanation(
            OverlapResult overlap,
            int sharedTopicsCount,
            ReciprocalPairRow pair) {
        String summary = String.format(
                "Coincidencia recíproca válida (%s ↔ %s): %d min semanales en común y %d tema(s) afines.",
                pair.myTeachesSkillSlug(),
                pair.candTeachesSkillSlug(),
                overlap.commonWeeklyMinutes(),
                sharedTopicsCount);
        return new MatchExplanation(
                overlap.commonWeeklyMinutes(),
                overlap.overlappingWindows(),
                sharedTopicsCount,
                summary);
    }

    private static MatchSearchResponse emptySearchResponse() {
        return new MatchSearchResponse(
                true,
                false,
                "Aún no hay parejas con coincidencia recíproca válida y disponibilidad común. Tu búsqueda sigue activa.",
                List.of());
    }

    private record MinuteInterval(int startMinute, int endMinute) {
    }

    public record OverlapResult(int commonWeeklyMinutes, List<String> overlappingWindows) {
    }

    public record PairEvaluation(boolean compatible, double score, MatchExplanation explanation) {
    }
}

