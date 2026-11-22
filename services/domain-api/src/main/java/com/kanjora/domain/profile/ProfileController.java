package com.kanjora.domain.profile;

import com.kanjora.domain.profile.ProfileDtos.UserProfileResponse;
import com.kanjora.domain.profile.ProfileDtos.UserProfileUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public UserProfileResponse getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.getMyProfile(jwt.getSubject());
    }

    @PutMapping("/me")
    public UserProfileResponse upsertMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserProfileUpsertRequest request) {
        return profileService.upsertMyProfile(jwt.getSubject(), request);
    }
}

