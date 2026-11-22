package com.kanjora.domain.confirmation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanjora.domain.confirmation.ConfirmationRepository.ExistingFactReport;
import com.kanjora.domain.confirmation.ConfirmationRepository.ProposalParticipants;
import com.kanjora.domain.confirmation.SessionFactsDtos.ParticipantAttendanceFact;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportRequest;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportResponse;
import com.kanjora.domain.confirmation.SessionFactsDtos.TerminalSessionState;
import com.kanjora.domain.shared.DomainException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionFactIngestionService {

    private final ConfirmationRepository repository;
    private final ObjectMapper jsonMapper;

    public SessionFactIngestionService(ConfirmationRepository repository, ObjectMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public SessionFactsReportResponse recordSessionFacts(
            UUID proposalIdPath,
            String idempotencyKey,
            SessionFactsReportRequest body) {

        if (idempotencyKey == null || !idempotencyKey.equals(body.factReportId())) {
            throw DomainException.unprocessable(
                    "IDEMPOTENCY_KEY_MISMATCH",
                    "Idempotency-Key debe coincidir con factReportId.");
        }
        if (!proposalIdPath.equals(body.proposalId())) {
            throw DomainException.unprocessable(
                    "PROPOSAL_ID_MISMATCH",
                    "El identificador de propuesta en ruta y cuerpo no coincide.");
        }

        Optional<ExistingFactReport> existingOpt = repository.findFactReportById(body.factReportId());
        if (existingOpt.isPresent()) {
            ExistingFactReport existing = existingOpt.get();
            if (!existing.proposalId().equals(proposalIdPath)) {
                throw DomainException.unprocessable(
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "factReportId pertenece a otra propuesta.");
            }
            String status = existing.resolutionStatus() != null
                    ? existing.resolutionStatus()
                    : "AWAITING_BILATERAL_CONFIRMATION";
            return new SessionFactsReportResponse(body.factReportId(), proposalIdPath, status, true);
        }

        ProposalParticipants proposal = repository.findProposalParticipants(proposalIdPath)
                .orElseThrow(() -> DomainException.notFound(
                        "PROPOSAL_NOT_FOUND",
                        "Propuesta no encontrada."));

        Set<String> expectedUsers = Set.of(proposal.proposerUserId(), proposal.recipientUserId());
        Set<String> reportedUsers = body.participantsAttendance().stream()
                .map(ParticipantAttendanceFact::userId)
                .collect(Collectors.toSet());
        if (!expectedUsers.equals(reportedUsers)) {
            throw DomainException.unprocessable(
                    "PARTICIPANTS_MISMATCH",
                    "Los participantes reportados no corresponden a la propuesta.");
        }

        String attendanceJson;
        try {
            attendanceJson = jsonMapper.writeValueAsString(body.participantsAttendance());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Error serializando asistencia", ex);
        }

        repository.insertFactReport(body, attendanceJson);

        String status;
        if (body.terminalSessionState() == TerminalSessionState.CANCELLED_BEFORE_START) {
            status = "CANCELLED_NO_PENALTY";
        } else if (body.terminalSessionState() == TerminalSessionState.COMPLETED_OBSERVED
                && !body.technicalIncidentObserved()) {
            status = "AWAITING_BILATERAL_CONFIRMATION";
        } else {
            status = "PENDING_REVIEW";
        }

        repository.upsertResolution(
                proposalIdPath,
                body.sessionId(),
                status,
                "CANCELLED_NO_PENALTY".equals(status));
        if ("PENDING_REVIEW".equals(status)) {
            repository.updateProposalStatus(proposalIdPath, "PENDING_REVIEW");
        }

        return new SessionFactsReportResponse(body.factReportId(), proposalIdPath, status, false);
    }
}

