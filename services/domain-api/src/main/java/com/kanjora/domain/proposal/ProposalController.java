package com.kanjora.domain.proposal;

import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationRequest;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.ExchangeHistoryItemResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.PilotMetricsResponse;
import com.kanjora.domain.confirmation.BilateralConfirmationService;
import com.kanjora.domain.confirmation.SessionFactIngestionService;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportRequest;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportResponse;
import com.kanjora.domain.proposal.ProposalDtos.CreateProposalRequest;
import com.kanjora.domain.proposal.ProposalDtos.ExchangeProposalResponse;
import com.kanjora.domain.proposal.ProposalDtos.ProposalValidationResponse;
import com.kanjora.domain.proposal.ProposalDtos.RespondProposalRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProposalController {

    private final ProposalService proposalService;
    private final SessionFactIngestionService factIngestionService;
    private final BilateralConfirmationService confirmationService;

    public ProposalController(
            ProposalService proposalService,
            SessionFactIngestionService factIngestionService,
            BilateralConfirmationService confirmationService) {
        this.proposalService = proposalService;
        this.factIngestionService = factIngestionService;
        this.confirmationService = confirmationService;
    }

    @PostMapping("/api/v1/exchange-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public ExchangeProposalResponse createProposal(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProposalRequest request) {
        return proposalService.createProposal(jwt.getSubject(), request);
    }

    @PostMapping("/api/v1/exchange-proposals/{proposalId}/respond")
    public ExchangeProposalResponse respondToProposal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId,
            @Valid @RequestBody RespondProposalRequest request) {
        return proposalService.respondToProposal(jwt.getSubject(), proposalId, request);
    }

    @GetMapping("/internal/v1/exchange-proposals/{proposalId}/validation")
    public ProposalValidationResponse validateProposalForScheduling(@PathVariable UUID proposalId) {
        return proposalService.validateProposalForScheduling(proposalId);
    }

    @PostMapping("/internal/v1/exchanges/{proposalId}/session-facts")
    public SessionFactsReportResponse recordSessionFacts(
            @PathVariable UUID proposalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SessionFactsReportRequest request) {
        return factIngestionService.recordSessionFacts(proposalId, idempotencyKey, request);
    }

    @PutMapping("/api/v1/exchanges/{proposalId}/confirmations/me")
    public BilateralConfirmationResponse confirmExchange(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId,
            @Valid @RequestBody BilateralConfirmationRequest request) {
        return confirmationService.confirmExchange(jwt.getSubject(), proposalId, request);
    }

    @GetMapping("/api/v1/exchanges/history/me")
    public List<ExchangeHistoryItemResponse> getMyExchangeHistory(@AuthenticationPrincipal Jwt jwt) {
        return confirmationService.getMyExchangeHistory(jwt.getSubject());
    }

    @GetMapping("/api/v1/admin/metrics/pilot")
    public PilotMetricsResponse getPilotMetrics() {
        return confirmationService.getPilotMetrics();
    }
}

