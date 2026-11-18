package com.kanjora.domain.profile;

import com.kanjora.domain.catalog.CatalogRepository;
import com.kanjora.domain.profile.ProfileDtos.AvailabilitySlotDto;
import com.kanjora.domain.profile.ProfileDtos.LearningGoalInputDto;
import com.kanjora.domain.profile.ProfileDtos.TeachingOfferInputDto;
import com.kanjora.domain.profile.ProfileDtos.UserProfileResponse;
import com.kanjora.domain.profile.ProfileDtos.UserProfileUpsertRequest;
import com.kanjora.domain.shared.DomainException;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final CatalogRepository catalogRepository;

    public ProfileService(ProfileRepository profileRepository, CatalogRepository catalogRepository) {
        this.profileRepository = profileRepository;
        this.catalogRepository = catalogRepository;
    }

    public UserProfileResponse getMyProfile(String userId) {
        return profileRepository.findProfile(userId)
                .orElseThrow(() -> DomainException.notFound(
                        "PROFILE_NOT_FOUND",
                        "El usuario autenticado aún no tiene un perfil creado."));
    }

    @Transactional
    public UserProfileResponse upsertMyProfile(String userId, UserProfileUpsertRequest request) {
        validateTimezone(request.timezone());
        validateAvailabilitySlots(request.availabilitySlots());
        validateLearningGoals(request.learningGoals());
        validateTeachingOffers(request.teachingOffers());

        profileRepository.upsertProfile(userId, request);
        return getMyProfile(userId);
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            throw DomainException.unprocessable(
                    "INVALID_TIMEZONE",
                    "La zona horaria IANA no es válida: " + timezone);
        }
    }

    private void validateAvailabilitySlots(List<AvailabilitySlotDto> slots) {
        record ParsedSlot(int dayOfWeek, LocalTime start, LocalTime end) {
        }

        List<ParsedSlot> parsed = slots.stream()
                .map(s -> {
                    LocalTime start = LocalTime.parse(s.startTimeLocal());
                    LocalTime end = LocalTime.parse(s.endTimeLocal());
                    if (!end.isAfter(start)) {
                        throw DomainException.unprocessable(
                                "INVALID_AVAILABILITY_RANGE",
                                "La hora de fin debe ser posterior a la de inicio en el día " + s.dayOfWeek());
                    }
                    return new ParsedSlot(s.dayOfWeek(), start, end);
                })
                .toList();

        Map<Integer, List<ParsedSlot>> byDay = parsed.stream()
                .collect(Collectors.groupingBy(ParsedSlot::dayOfWeek));

        for (Map.Entry<Integer, List<ParsedSlot>> entry : byDay.entrySet()) {
            List<ParsedSlot> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(ParsedSlot::start))
                    .toList();
            for (int i = 1; i < sorted.size(); i++) {
                ParsedSlot previous = sorted.get(i - 1);
                ParsedSlot current = sorted.get(i);
                if (current.start().isBefore(previous.end())) {
                    throw DomainException.unprocessable(
                            "OVERLAPPING_AVAILABILITY_SLOTS",
                            "Existen franjas horarias solapadas en el día " + entry.getKey());
                }
            }
        }
    }

    private void validateLearningGoals(List<LearningGoalInputDto> goals) {
        Set<UUID> seenSkills = new HashSet<>();
        for (LearningGoalInputDto goal : goals) {
            if (!seenSkills.add(goal.skillId())) {
                throw DomainException.unprocessable(
                        "DUPLICATE_LEARNING_GOAL_SKILL",
                        "No se puede repetir la misma habilidad en metas de aprendizaje: " + goal.skillId());
            }
            validateSkillLevelAndTopics(goal.skillId(), goal.targetLevelId(), goal.topicIds());
        }
    }

    private void validateTeachingOffers(List<TeachingOfferInputDto> offers) {
        record SkillLevelKey(UUID skillId, UUID levelId) {
        }
        Set<SkillLevelKey> seenOffers = new HashSet<>();
        for (TeachingOfferInputDto offer : offers) {
            if (!seenOffers.add(new SkillLevelKey(offer.skillId(), offer.levelId()))) {
                throw DomainException.unprocessable(
                        "DUPLICATE_TEACHING_OFFER",
                        "Oferta de enseñanza duplicada para la misma habilidad y nivel.");
            }
            validateSkillLevelAndTopics(offer.skillId(), offer.levelId(), offer.topicIds());
        }
    }

    private void validateSkillLevelAndTopics(UUID skillId, UUID levelId, List<UUID> topicIds) {
        if (!catalogRepository.skillAndLevelExist(skillId, levelId)) {
            throw DomainException.unprocessable(
                    "INVALID_CATALOG_REFERENCE",
                    "La habilidad o el nivel indicado no existe o no está activo en el catálogo.");
        }
        if (!catalogRepository.allTopicsBelongToSkill(skillId, topicIds)) {
            throw DomainException.unprocessable(
                    "INVALID_TOPIC_FOR_SKILL",
                    "Uno o más temas no existen o no pertenecen a la habilidad indicada.");
        }
    }
}

