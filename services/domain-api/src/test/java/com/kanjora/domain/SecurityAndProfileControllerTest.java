package com.kanjora.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanjora.domain.catalog.CatalogController;
import com.kanjora.domain.catalog.CatalogRepository;
import com.kanjora.domain.config.SecurityConfig;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.BilateralConfirmationRequest;
import com.kanjora.domain.confirmation.BilateralConfirmationDtos.OutcomeDeclaration;
import com.kanjora.domain.confirmation.BilateralConfirmationService;
import com.kanjora.domain.confirmation.ConfirmationRepository;
import com.kanjora.domain.confirmation.ConfirmationRepository.ConfirmationEntry;
import com.kanjora.domain.confirmation.ConfirmationRepository.ExistingFactReport;
import com.kanjora.domain.confirmation.ConfirmationRepository.ProposalParticipants;
import com.kanjora.domain.confirmation.ConfirmationRepository.ResolutionRecord;
import com.kanjora.domain.confirmation.SessionFactIngestionService;
import com.kanjora.domain.confirmation.SessionFactsDtos.ObservedPresence;
import com.kanjora.domain.confirmation.SessionFactsDtos.ParticipantAttendanceFact;
import com.kanjora.domain.confirmation.SessionFactsDtos.SessionFactsReportRequest;
import com.kanjora.domain.confirmation.SessionFactsDtos.TerminalSessionState;
import com.kanjora.domain.matching.MatchingRepository;
import com.kanjora.domain.matching.MatchingRepository.AvailabilityWindow;
import com.kanjora.domain.matching.MatchingRepository.ReciprocalPairRow;
import com.kanjora.domain.matching.MatchingRepository.UserMatchingContext;
import com.kanjora.domain.matching.MatchingService;
import com.kanjora.domain.profile.ProfileController;
import com.kanjora.domain.profile.ProfileDtos.AvailabilitySlotDto;
import com.kanjora.domain.profile.ProfileDtos.LearningGoalInputDto;
import com.kanjora.domain.profile.ProfileDtos.TeachingOfferResponseDto;
import com.kanjora.domain.profile.ProfileDtos.UserProfileResponse;
import com.kanjora.domain.profile.ProfileRepository;
import com.kanjora.domain.profile.ProfileService;
import com.kanjora.domain.qualification.QualificationDtos.MiniClassReviewRequest;
import com.kanjora.domain.qualification.QualificationDtos.ReviewDecision;
import com.kanjora.domain.qualification.QualificationRepository;
import com.kanjora.domain.qualification.QualificationRepository.SubmissionContext;
import com.kanjora.domain.qualification.QualificationService;
import com.kanjora.domain.shared.ApiExceptionHandler;
import com.kanjora.domain.shared.DomainException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@WebMvcTest(controllers = {ProfileController.class, CatalogController.class})
@Import({
    SecurityConfig.class,
    ApiExceptionHandler.class,
    ProfileService.class,
    SecurityAndProfileControllerTest.NoOpTxConfig.class
})
class SecurityAndProfileControllerTest {

    private static final UUID SKILL_BACKEND = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID LEVEL_BACKEND_INTERMEDIO = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID TOPIC_REST = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID SKILL_ENGLISH = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID LEVEL_ENGLISH_B1 = UUID.fromString("30000000-0000-4000-8000-000000000012");
    private static final UUID TOPIC_INTERVIEWS = UUID.fromString("40000000-0000-4000-8000-000000000011");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProfileRepository profileRepository;

    @MockBean
    private CatalogRepository catalogRepository;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedRequestsAreRejectedWith401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/catalog/skills"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void standardUserJwtCannotAccessInternalOrAdminRoutes() throws Exception {
        var userJwt = jwt().jwt(j -> j
                .subject("usr_alice_123")
                .audience(List.of("kanjora-api"))
                .claim("roles", List.of("USER")));

        mockMvc.perform(get("/internal/v1/exchange-proposals/a1b2c3d4-e5f6-7890-abcd-ef1234567890/validation")
                        .with(userJwt))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/mini-class-submissions/a1b2c3d4-e5f6-7890-abcd-ef1234567890/review")
                        .with(userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewerComments\":\"Excelente clase de prueba\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void rejectsOverlappingAvailabilitySlotsWith422() throws Exception {
        String payload = """
                {
                  "displayName": "Alice Dev",
                  "bio": "Backend engineer",
                  "timezone": "America/Bogota",
                  "availabilitySlots": [
                    { "dayOfWeek": 2, "startTimeLocal": "18:00", "endTimeLocal": "19:30" },
                    { "dayOfWeek": 2, "startTimeLocal": "19:00", "endTimeLocal": "20:00" }
                  ],
                  "learningGoals": [],
                  "teachingOffers": []
                }
                """;

        mockMvc.perform(put("/api/v1/profiles/me")
                        .with(jwt().jwt(j -> j.subject("usr_alice_123").claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("OVERLAPPING_AVAILABILITY_SLOTS"));
    }

    @Test
    void rejectsInvalidCatalogReferenceWith422() throws Exception {
        when(catalogRepository.skillAndLevelExist(SKILL_BACKEND, LEVEL_ENGLISH_B1)).thenReturn(false);

        String payload = """
                {
                  "displayName": "Alice Dev",
                  "timezone": "America/Bogota",
                  "availabilitySlots": [
                    { "dayOfWeek": 1, "startTimeLocal": "18:00", "endTimeLocal": "19:00" }
                  ],
                  "learningGoals": [
                    { "skillId": "%s", "targetLevelId": "%s", "topicIds": [] }
                  ],
                  "teachingOffers": []
                }
                """.formatted(SKILL_BACKEND, LEVEL_ENGLISH_B1);

        mockMvc.perform(put("/api/v1/profiles/me")
                        .with(jwt().jwt(j -> j.subject("usr_alice_123").claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CATALOG_REFERENCE"));
    }

    @Test
    void upsertsProfileBoundStrictlyToJwtSubject() throws Exception {
        when(catalogRepository.skillAndLevelExist(SKILL_ENGLISH, LEVEL_ENGLISH_B1)).thenReturn(true);
        when(catalogRepository.allTopicsBelongToSkill(SKILL_ENGLISH, List.of(TOPIC_INTERVIEWS))).thenReturn(true);
        when(catalogRepository.skillAndLevelExist(SKILL_BACKEND, LEVEL_BACKEND_INTERMEDIO)).thenReturn(true);
        when(catalogRepository.allTopicsBelongToSkill(SKILL_BACKEND, List.of(TOPIC_REST))).thenReturn(true);

        UserProfileResponse expected = new UserProfileResponse(
                "usr_alice_123",
                "Alice Dev",
                "Backend engineer",
                "America/Bogota",
                List.of(new AvailabilitySlotDto(3, "18:00", "20:00")),
                List.of(new LearningGoalInputDto(SKILL_ENGLISH, LEVEL_ENGLISH_B1, List.of(TOPIC_INTERVIEWS))),
                List.of(new TeachingOfferResponseDto(
                        SKILL_BACKEND,
                        LEVEL_BACKEND_INTERMEDIO,
                        "NOT_STARTED",
                        List.of(TOPIC_REST))),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(profileRepository.findProfile("usr_alice_123")).thenReturn(Optional.of(expected));

        String payload = """
                {
                  "displayName": "Alice Dev",
                  "bio": "Backend engineer",
                  "timezone": "America/Bogota",
                  "availabilitySlots": [
                    { "dayOfWeek": 3, "startTimeLocal": "18:00", "endTimeLocal": "20:00" }
                  ],
                  "learningGoals": [
                    { "skillId": "%s", "targetLevelId": "%s", "topicIds": ["%s"] }
                  ],
                  "teachingOffers": [
                    { "skillId": "%s", "levelId": "%s", "topicIds": ["%s"] }
                  ]
                }
                """.formatted(
                SKILL_ENGLISH, LEVEL_ENGLISH_B1, TOPIC_INTERVIEWS,
                SKILL_BACKEND, LEVEL_BACKEND_INTERMEDIO, TOPIC_REST);

        mockMvc.perform(put("/api/v1/profiles/me")
                        .with(jwt().jwt(j -> j
                                .subject("usr_alice_123")
                                .audience(List.of("kanjora-api"))
                                .claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("usr_alice_123"))
                .andExpect(jsonPath("$.displayName").value("Alice Dev"))
                .andExpect(jsonPath("$.teachingOffers[0].qualificationStatus").value("NOT_STARTED"));

        verify(profileRepository).upsertProfile(eq("usr_alice_123"), any());
    }

    @Test
    void jwtValidatorsEnforceRequiredAudienceAndSubject() {
        var audienceValidator = new SecurityConfig.AudienceValidator("kanjora-api");
        var subjectValidator = new SecurityConfig.SubjectValidator();

        Jwt validToken = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("usr_alice_123")
                .audience(List.of("kanjora-api"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        assertThat(audienceValidator.validate(validToken).hasErrors()).isFalse();
        assertThat(subjectValidator.validate(validToken).hasErrors()).isFalse();

        Jwt wrongAudience = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("usr_alice_123")
                .audience(List.of("other-api"))
                .build();
        assertThat(audienceValidator.validate(wrongAudience).hasErrors()).isTrue();

        Jwt blankSubject = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("   ")
                .audience(List.of("kanjora-api"))
                .build();
        assertThat(subjectValidator.validate(blankSubject).hasErrors()).isTrue();
    }

    @Test
    void matchingCalculatesCrossTimezoneOverlapAndExplanation() {
        var matchingRepo = mock(MatchingRepository.class);
        when(matchingRepo.countSharedTopicsBetweenPair(any(), any(), any(), any(), any(), any())).thenReturn(1);
        var matchingService = new MatchingService(matchingRepo);

        var alice = new UserMatchingContext(
                "usr_alice", "Alice", "America/Bogota", true, true,
                List.of(new AvailabilityWindow(3, LocalTime.of(18, 0), LocalTime.of(20, 0))));
        var bobSlots = List.of(new AvailabilityWindow(4, LocalTime.of(0, 30), LocalTime.of(2, 0)));
        var pair = new ReciprocalPairRow(
                "usr_bob", "Bob", "Europe/Madrid",
                UUID.randomUUID(), SKILL_BACKEND, "programacion-backend", LEVEL_BACKEND_INTERMEDIO,
                UUID.randomUUID(), SKILL_ENGLISH, "ingles-tecnico", LEVEL_ENGLISH_B1, LEVEL_BACKEND_INTERMEDIO);

        var eval = matchingService.evaluatePairCompatibility(alice, pair, bobSlots);
        assertThat(eval.compatible()).isTrue();
        assertThat(eval.explanation().commonWeeklyMinutes()).isEqualTo(90);
        assertThat(eval.explanation().sharedTopicsCount()).isEqualTo(1);
    }

    @Test
    void qualificationRejectsSelfReviewAndConfirmationResolvesAgreementVsDispute() {
        var qualRepo = mock(QualificationRepository.class);
        var subId = UUID.randomUUID();
        when(qualRepo.findSubmissionContext(subId)).thenReturn(
                Optional.of(new SubmissionContext(subId, UUID.randomUUID(), "PENDING_REVIEW", "usr_alice")));
        var qualService = new QualificationService(qualRepo, catalogRepository);

        assertThatThrownBy(() -> qualService.reviewMiniClass(
                "usr_alice", subId, new MiniClassReviewRequest(ReviewDecision.APPROVE, "Buen trabajo propio")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("propia mini clase");

        var confRepo = mock(ConfirmationRepository.class);
        var mapper = new ObjectMapper();
        var proposalId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var factReq = new SessionFactsReportRequest(
                "fct_replay_001", sessionId, proposalId, TerminalSessionState.COMPLETED_OBSERVED,
                OffsetDateTime.now(ZoneOffset.UTC),
                List.of(
                        new ParticipantAttendanceFact("usr_alice", null, null, 3600, 0, ObservedPresence.PRESENT_FULL),
                        new ParticipantAttendanceFact("usr_bob", null, null, 3600, 0, ObservedPresence.PRESENT_FULL)),
                false, null);
        when(confRepo.findFactReportById("fct_replay_001"))
                .thenReturn(Optional.of(new ExistingFactReport("fct_replay_001", proposalId, "AWAITING_BILATERAL_CONFIRMATION")));

        var factService = new SessionFactIngestionService(confRepo, mapper);
        var replayResp = factService.recordSessionFacts(proposalId, "fct_replay_001", factReq);
        assertThat(replayResp.idempotentReplay()).isTrue();

        when(confRepo.findProposalParticipants(proposalId))
                .thenReturn(Optional.of(new ProposalParticipants(proposalId, "usr_alice", "usr_bob", "ACCEPTED")));
        when(confRepo.findResolutionByProposal(proposalId))
                .thenReturn(Optional.of(new ResolutionRecord(proposalId, sessionId, "AWAITING_PARTNER_CONFIRMATION")));
        when(confRepo.upsertConfirmation(eq(proposalId), eq("usr_bob"), any()))
                .thenReturn(OffsetDateTime.now(ZoneOffset.UTC));
        when(confRepo.findConfirmations(proposalId, sessionId)).thenReturn(List.of(
                new ConfirmationEntry("usr_alice", OutcomeDeclaration.COMPLETED, true, OffsetDateTime.now(ZoneOffset.UTC)),
                new ConfirmationEntry("usr_bob", OutcomeDeclaration.PARTNER_ABANDONED, false, OffsetDateTime.now(ZoneOffset.UTC))));

        var confService = new BilateralConfirmationService(confRepo, mapper);
        var disputeResp = confService.confirmExchange("usr_bob", proposalId,
                new BilateralConfirmationRequest(sessionId, OutcomeDeclaration.PARTNER_ABANDONED, false, null, "Se retiró", null, false));
        assertThat(disputeResp.exchangeResolutionStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(disputeResp.creditEligibilityFactRecorded()).isFalse();
    }

    @TestConfiguration
    static class NoOpTxConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}

