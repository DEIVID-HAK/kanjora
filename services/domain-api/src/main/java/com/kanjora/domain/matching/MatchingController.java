package com.kanjora.domain.matching;

import com.kanjora.domain.matching.MatchingDtos.MatchSearchResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/matches")
public class MatchingController {

    private final MatchingService matchingService;

    public MatchingController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @GetMapping
    public MatchSearchResponse getValidMatches(@AuthenticationPrincipal Jwt jwt) {
        return matchingService.findValidMatches(jwt.getSubject());
    }
}

