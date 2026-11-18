package com.kanjora.domain.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanjora.domain.matching.MatchingRepository;
import com.kanjora.domain.matching.MatchingRepository.AvailabilityWindow;
import com.kanjora.domain.matching.MatchingRepository.ReciprocalPairRow;
import com.kanjora.domain.matching.MatchingRepository.UserMatchingContext;
import com.kanjora.domain.matching.MatchingService;
import com.kanjora.domain.matching.MatchingService.PairEvaluation;
import com.kanjora.domain.proposal.ProposalDtos.CreateProposalRequest;
import com.kanjora.domain.proposal.ProposalDtos.ExchangeProposalResponse;
import com.kanjora.domain.proposal.ProposalDtos.LearnsValidationInfo;
import com.kanjora.domain.proposal.ProposalDtos.ParticipantValidationInfo;
import com.kanjora.domain.proposal.ProposalDtos.ProposalDecision;
import com.kanjora.domain.proposal.ProposalDtos.ProposalValidationResponse;
import com.kanjora.domain.proposal.ProposalDtos.RespondProposalRequest;
import com.kanjora.domain.proposal.ProposalDtos.TeachesValidationInfo;
import com.kanjora.domain.proposal.ProposalRepository.ProposalValidationContext;
import com.kanjora.domain.shared.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalService {

    private final ProposalRepository proposalRepository;
    private final MatchingRepository matchingRepository;
    private final MatchingService matchingService;
    private final ObjectMapper objectMapper;

    public ProposalService(
            ProposalRepository proposalRepository,
            MatchingRepository matchingRepository,
            MatchingService matchingService,
            ObjectMapper objectMapper) {
        this.proposalRepository = proposalRepository;
        this.matchingRepository = matchingRepository;
        this.matchingService = matchingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExchangeProposalResponse createProposal(String proposerUserId, CreateProposalRequest req) {
        if (proposerUserId.equals(req.recipientUserId())) {
            throw DomainException.unprocessable(
                    "CANNOT_PROPOSE_TO_SELF",
                    "No puedes enviarte una propuesta de intercambio a ti mismo.");
        }

        if (proposalRepository.hasActiveProposalBetween(proposerUserId, req.recipientUserId())) {
            throw DomainException.conflict(
                    "DUPLICATE_ACTIVE_PROPOSAL",
                    "Ya existe una propuesta pendiente o aceptada con esta persona.");
        }

        UserMatchingContext proposerCtx = matchingRepository.findUserContext(proposerUserId)
                .orElseThrow(() -> DomainException.conflict(
                        "PROFILE_REQUIRED",
                        "Debes completar tu perfil antes de proponer un intercambio."));

        ReciprocalPairRow pair = matchingRepository.findSpecificReciprocalPair(
                proposerUserId,
                req.recipientUserId(),
                req.proposerTeachesSkillId(),
                req.proposerTeachesLevelId(),
                req.recipientTeachesSkillId(),
                req.recipientTeachesLevelId())
                .orElseThrow(() -> DomainException.conflict(
                        "INVALID_RECIPROCAL_MATCH",
                        "Alguna de las partes no está habilitada para enseñar lo que la otra busca o sus niveles no son compatibles."));

        List<AvailabilityWindow> recipientSlots = matchingRepository.findAvailabilitySlots(req.recipientUserId());
        PairEvaluation evaluation = matchingService.evaluatePairCompatibility(proposerCtx, pair, recipientSlots);
        if (!evaluation.compatible()) {
            throw DomainException.conflict(
                    "NO_COMMON_AVAILABILITY",
                    "La pareja no comparte al menos 30 minutos semanales de disponibilidad común.");
        }

        String explanationJson;
        try {
            explanationJson = objectMapper.writeValueAsString(evaluation.explanation());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize match explanation", ex);
        }

        String proposerTopic = normalizeTopic(req.proposerTopicCode());
        String recipientTopic = normalizeTopic(req.recipientTopicCode());

        return proposalRepository.insertProposal(
                UUID.randomUUID(),
                proposerUserId,
                req.recipientUserId(),
                req.proposerTeachesSkillId(),
                req.proposerTeachesLevelId(),
                pair.myQualificationId(),
                proposerTopic,
                req.recipientTeachesSkillId(),
                req.recipientTeachesLevelId(),
                pair.candQualificationId(),
                recipientTopic,
                evaluation.score(),
                explanationJson);
    }

    @Transactional
    public ExchangeProposalResponse respondToProposal(
            String actorUserId,
            UUID proposalId,
            RespondProposalRequest req) {

        ExchangeProposalResponse existing = proposalRepository.findById(proposalId)
                .orElseThrow(() -> DomainException.notFound(
                        "PROPOSAL_NOT_FOUND",
                        "La propuesta de intercambio no existe."));

        if (!existing.recipientUserId().equals(actorUserId)) {
            throw DomainException.forbidden(
                    "ONLY_RECIPIENT_CAN_RESPOND",
                    "Solo la persona destinataria puede aceptar o rechazar esta propuesta.");
        }

        if (!"PENDING_RECIPIENT".equals(existing.status())) {
            throw DomainException.conflict(
                    "PROPOSAL_ALREADY_DECIDED",
                    "La propuesta ya se encuentra en estado " + existing.status() + ".");
        }

        String newStatus = req.decision() == ProposalDecision.ACCEPT ? "ACCEPTED" : "REJECTED";
        return proposalRepository.updateDecision(proposalId, newStatus, req.reason());
    }

    public ProposalValidationResponse validateProposalForScheduling(UUID proposalId) {
        ProposalValidationContext ctx = proposalRepository.findValidationContext(proposalId)
                .orElseThrow(() -> DomainException.notFound(
                        "PROPOSAL_NOT_FOUND",
                        "La propuesta de intercambio no existe."));

        boolean proposerQualified = "APPROVED".equals(ctx.proposerQualificationStatus());
        boolean recipientQualified = "APPROVED".equals(ctx.recipientQualificationStatus());

        String invalidationReason = null;
        boolean validForScheduling = true;
        if ("COMPLETED".equals(ctx.status())) {
            validForScheduling = false;
            invalidationReason = "EXCHANGE_ALREADY_COMPLETED";
        } else if (!"ACCEPTED".equals(ctx.status())) {
            validForScheduling = false;
            invalidationReason = "PROPOSAL_NOT_ACCEPTED";
        } else if (!proposerQualified || !recipientQualified) {
            validForScheduling = false;
            invalidationReason = "TEACHER_QUALIFICATION_REVOKED";
        }

        ParticipantValidationInfo proposerInfo = new ParticipantValidationInfo(
                ctx.proposerUserId(),
                new TeachesValidationInfo(
                        ctx.proposerTeachesSkillId(),
                        ctx.proposerTeachesSkillSlug(),
                        ctx.proposerTeachesLevelId(),
                        ctx.proposerTopicCode(),
                        ctx.proposerQualificationId(),
                        proposerQualified),
                new LearnsValidationInfo(ctx.recipientTeachesSkillId(), ctx.proposerLearnsLevelId()));

        ParticipantValidationInfo recipientInfo = new ParticipantValidationInfo(
                ctx.recipientUserId(),
                new TeachesValidationInfo(
                        ctx.recipientTeachesSkillId(),
                        ctx.recipientTeachesSkillSlug(),
                        ctx.recipientTeachesLevelId(),
                        ctx.recipientTopicCode(),
                        ctx.recipientQualificationId(),
                        recipientQualified),
                new LearnsValidationInfo(ctx.proposerTeachesSkillId(), ctx.recipientLearnsLevelId()));

        List<UUID> guideIds = proposalRepository.findRecommendedGuideIds(
                ctx.proposerTeachesSkillId(),
                ctx.proposerTeachesLevelId(),
                ctx.recipientTeachesSkillId(),
                ctx.recipientTeachesLevelId());

        return new ProposalValidationResponse(
                ctx.proposalId(),
                ctx.status(),
                validForScheduling,
                invalidationReason,
                List.of(proposerInfo, recipientInfo),
                guideIds);
    }

    private static String normalizeTopic(String topicCode) {
        return (topicCode == null || topicCode.isBlank()) ? "general" : topicCode.trim();
    }
}

