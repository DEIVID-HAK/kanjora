package com.kanjora.domain.qualification;

import com.kanjora.domain.catalog.CatalogRepository;
import com.kanjora.domain.qualification.QualificationDtos.CreateQualificationRequest;
import com.kanjora.domain.qualification.QualificationDtos.MiniClassReviewRequest;
import com.kanjora.domain.qualification.QualificationDtos.MiniClassSubmissionRequest;
import com.kanjora.domain.qualification.QualificationDtos.PendingSubmissionResponse;
import com.kanjora.domain.qualification.QualificationDtos.QualificationResponse;
import com.kanjora.domain.qualification.QualificationRepository.SubmissionContext;
import com.kanjora.domain.shared.DomainException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualificationService {

    private final QualificationRepository qualificationRepository;
    private final CatalogRepository catalogRepository;

    public QualificationService(
            QualificationRepository qualificationRepository,
            CatalogRepository catalogRepository) {
        this.qualificationRepository = qualificationRepository;
        this.catalogRepository = catalogRepository;
    }

    @Transactional
    public QualificationResponse upsertQualificationProgress(String userId, CreateQualificationRequest req) {
        if (!qualificationRepository.userProfileExists(userId)) {
            throw DomainException.conflict(
                    "PROFILE_REQUIRED",
                    "Debes completar tu perfil antes de iniciar una habilitación para enseñar.");
        }
        if (!catalogRepository.skillAndLevelExist(req.skillId(), req.levelId())) {
            throw DomainException.unprocessable(
                    "INVALID_CATALOG_REFERENCE",
                    "La habilidad o nivel indicado no existe o no está activo.");
        }

        UUID qualificationId = qualificationRepository.upsertProgress(
                userId,
                req.skillId(),
                req.levelId(),
                req.methodologyAcknowledged(),
                req.practicalTestPassed(),
                req.practicalTestReference());

        return qualificationRepository.findById(qualificationId)
                .orElseThrow(() -> DomainException.notFound("QUALIFICATION_NOT_FOUND", "Habilitación no encontrada."));
    }

    @Transactional
    public QualificationResponse submitMiniClass(
            String userId,
            UUID qualificationId,
            MiniClassSubmissionRequest req) {

        validateArtifactUri(req.recordingOrArtifactUrl());

        QualificationResponse qual = qualificationRepository.findById(qualificationId)
                .orElseThrow(() -> DomainException.notFound(
                        "QUALIFICATION_NOT_FOUND",
                        "La postulación de habilitación no existe."));

        if (!qual.userId().equals(userId)) {
            throw DomainException.forbidden(
                    "QUALIFICATION_OWNERSHIP_REQUIRED",
                    "No puedes enviar una mini clase en la habilitación de otra persona.");
        }

        if (!qual.methodologyAcknowledged() || !qual.practicalTestPassed()) {
            throw DomainException.conflict(
                    "PREREQUISITES_INCOMPLETE",
                    "Debes completar la guía metodológica y la prueba práctica breve antes de enviar tu mini clase.");
        }

        if ("PENDING_HUMAN_REVIEW".equals(qual.status())) {
            throw DomainException.conflict(
                    "REVIEW_ALREADY_PENDING",
                    "Ya existe una mini clase pendiente de revisión humana para esta habilidad y nivel.");
        }

        if ("APPROVED".equals(qual.status()) || "REVOKED".equals(qual.status())) {
            throw DomainException.conflict(
                    "INVALID_QUALIFICATION_STATE",
                    "La habilitación en estado " + qual.status() + " no admite nuevos envíos.");
        }

        qualificationRepository.createMiniClassSubmission(qualificationId, req);

        return qualificationRepository.findById(qualificationId)
                .orElseThrow(() -> DomainException.notFound("QUALIFICATION_NOT_FOUND", "Habilitación no encontrada."));
    }

    @Transactional
    public QualificationResponse reviewMiniClass(
            String reviewerUserId,
            UUID submissionId,
            MiniClassReviewRequest req) {

        SubmissionContext submission = qualificationRepository.findSubmissionContext(submissionId)
                .orElseThrow(() -> DomainException.notFound(
                        "SUBMISSION_NOT_FOUND",
                        "La entrega de mini clase indicada no existe."));

        if (submission.candidateUserId().equals(reviewerUserId)) {
            throw DomainException.forbidden(
                    "SELF_REVIEW_FORBIDDEN",
                    "Un revisor no puede aprobar o rechazar su propia mini clase.");
        }

        if (!"PENDING_REVIEW".equals(submission.reviewStatus())) {
            throw DomainException.conflict(
                    "SUBMISSION_ALREADY_REVIEWED",
                    "Esta entrega de mini clase ya fue revisada previamente.");
        }

        String targetStatus = req.decision().isApproval() ? "APPROVED" : "REJECTED";
        qualificationRepository.applyReviewDecision(
                submissionId,
                submission.qualificationId(),
                reviewerUserId,
                targetStatus,
                targetStatus,
                req.reviewerComments());

        return qualificationRepository.findById(submission.qualificationId())
                .orElseThrow(() -> DomainException.notFound("QUALIFICATION_NOT_FOUND", "Habilitación no encontrada."));
    }

    public List<PendingSubmissionResponse> listPendingSubmissions() {
        return qualificationRepository.findPendingSubmissions();
    }

    private void validateArtifactUri(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Unsupported URI scheme");
            }
        } catch (IllegalArgumentException ex) {
            throw DomainException.unprocessable(
                    "INVALID_ARTIFACT_URL",
                    "La URL de grabación o artefacto de la mini clase debe ser http/https válida.");
        }
    }
}

