package com.kanjora.domain.confirmation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationRequest;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.ExchangeHistoryItemResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.OutcomeDeclaration;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.PilotMetricsResponse;
import com.kanjora.domain.confirmation.ConfirmationRepository.ConfirmationEntry;
import com.kanjora.domain.confirmation.ConfirmationRepository.ProposalParticipants;
import com.kanjora.domain.confirmation.ConfirmationRepository.ResolutionRecord;
import com.kanjora.domain.shared.DomainException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BilateralConfirmationService {

    private final ConfirmationRepository repository;
    private final ObjectMapper jsonMapper;

    public BilateralConfirmationService(ConfirmationRepository repository, ObjectMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public BilateralConfirmationResponse confirmExchange(
            String actorUserId,
            UUID proposalId,
            BilateralConfirmationRequest body) {

        ProposalParticipants proposal = repository.findProposalParticipants(proposalId)
                .orElseThrow(() -> DomainException.notFound(
                        "PROPOSAL_NOT_FOUND",
                        "La propuesta de intercambio no existe."));

        if (!actorUserId.equals(proposal.proposerUserId()) && !actorUserId.equals(proposal.recipientUserId())) {
            throw DomainException.forbidden(
                    "NOT_EXCHANGE_PARTICIPANT",
                    "Solo los dos participantes pueden confirmar este intercambio.");
        }

        ResolutionRecord resolution = repository.findResolutionByProposal(proposalId)
                .orElseThrow(() -> DomainException.conflict(
                        "SESSION_FACTS_NOT_RECEIVED",
                        "Aún no se registran hechos de cierre desde el servicio de sesiones."));

        if (!resolution.sessionId().equals(body.sessionId())) {
            throw DomainException.unprocessable(
                    "SESSION_ID_MISMATCH",
                    "El sessionId no coincide con la sesión asociada a la propuesta.");
        }

        if ("CANCELLED_NO_PENALTY".equals(resolution.resolutionStatus())) {
            throw DomainException.conflict(
                    "EXCHANGE_CANCELLED",
                    "Una sesión cancelada antes de iniciar no requiere confirmación bilateral.");
        }

        if ("CONFIRMED".equals(resolution.resolutionStatus())) {
            Optional<ConfirmationEntry> prior = repository.findConfirmations(proposalId, body.sessionId())
                    .stream()
                    .filter(entry -> entry.userId().equals(actorUserId))
                    .findFirst();
            if (prior.isPresent()
                    && body.outcomeDeclaration() == OutcomeDeclaration.COMPLETED
                    && body.bothBlocksExecuted()) {
                return new BilateralConfirmationResponse(
                        proposalId,
                        body.sessionId(),
                        prior.get().confirmedAt(),
                        true,
                        "CONFIRMED",
                        true);
            }
            throw DomainException.conflict(
                    "EXCHANGE_ALREADY_CONFIRMED",
                    "Este intercambio ya fue confirmado por ambas partes.");
        }

        OffsetDateTime recordedAt = repository.upsertConfirmation(proposalId, actorUserId, body);
        List<ConfirmationEntry> entries = repository.findConfirmations(proposalId, body.sessionId());

        boolean partnerConfirmed = entries.size() == 2;
        boolean hasDisagreement = entries.stream()
                .anyMatch(e -> e.outcomeDeclaration() != OutcomeDeclaration.COMPLETED || !e.bothBlocksExecuted());
        boolean hasSessionIncident = repository.hasBlockingSessionIncident(proposalId, body.sessionId());

        if (hasDisagreement || hasSessionIncident) {
            repository.upsertResolution(proposalId, body.sessionId(), "PENDING_REVIEW", false);
            repository.updateProposalStatus(proposalId, "PENDING_REVIEW");
            return new BilateralConfirmationResponse(
                    proposalId, body.sessionId(), recordedAt, partnerConfirmed, "PENDING_REVIEW", false);
        }

        if (!partnerConfirmed) {
            repository.upsertResolution(proposalId, body.sessionId(), "AWAITING_PARTNER_CONFIRMATION", false);
            return new BilateralConfirmationResponse(
                    proposalId, body.sessionId(), recordedAt, false, "AWAITING_PARTNER_CONFIRMATION", false);
        }

        repository.upsertResolution(proposalId, body.sessionId(), "CONFIRMED", true);
        repository.updateProposalStatus(proposalId, "COMPLETED");
        repository.appendConfirmedExchangeFact(
                proposalId,
                body.sessionId(),
                proposal.proposerUserId(),
                proposal.recipientUserId(),
                buildConfirmedFactJson(proposalId, body.sessionId()));

        return new BilateralConfirmationResponse(
                proposalId, body.sessionId(), recordedAt, true, "CONFIRMED", true);
    }

    public List<ExchangeHistoryItemResponse> getMyExchangeHistory(String userId) {
        return repository.findUserExchangeHistory(userId);
    }

    public PilotMetricsResponse getPilotMetrics() {
        return repository.computePilotMetrics();
    }

    private String buildConfirmedFactJson(UUID proposalId, UUID sessionId) {
        try {
            return jsonMapper.writeValueAsString(Map.of(
                    "proposalId", proposalId.toString(),
                    "sessionId", sessionId.toString(),
                    "bilateralConfirmed", true,
                    "bothBlocksExecuted", true));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Error serializando hecho confirmado", ex);
        }
    }
}

