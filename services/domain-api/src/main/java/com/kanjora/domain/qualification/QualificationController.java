package com.kanjora.domain.qualification;

import com.kanjora.domain.qualification.QualificationDtos.CreateQualificationRequest;
import com.kanjora.domain.qualification.QualificationDtos.MiniClassReviewRequest;
import com.kanjora.domain.qualification.QualificationDtos.MiniClassSubmissionRequest;
import com.kanjora.domain.qualification.QualificationDtos.QualificationResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QualificationController {

    private final QualificationService qualificationService;

    public QualificationController(QualificationService qualificationService) {
        this.qualificationService = qualificationService;
    }

    @PostMapping("/api/v1/teaching-qualifications")
    public QualificationResponse upsertQualificationProgress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateQualificationRequest request) {
        return qualificationService.upsertQualificationProgress(jwt.getSubject(), request);
    }

    @PostMapping("/api/v1/teaching-qualifications/{qualificationId}/mini-class")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationResponse submitMiniClass(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID qualificationId,
            @Valid @RequestBody MiniClassSubmissionRequest request) {
        return qualificationService.submitMiniClass(jwt.getSubject(), qualificationId, request);
    }

    @PostMapping("/api/v1/admin/mini-class-submissions/{submissionId}/review")
    public QualificationResponse reviewMiniClass(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID submissionId,
            @Valid @RequestBody MiniClassReviewRequest request) {
        return qualificationService.reviewMiniClass(jwt.getSubject(), submissionId, request);
    }
}

