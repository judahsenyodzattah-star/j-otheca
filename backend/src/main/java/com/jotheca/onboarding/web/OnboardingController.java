package com.jotheca.onboarding.web;

import com.jotheca.onboarding.dto.CreateOnboardingRequest;
import com.jotheca.onboarding.dto.OnboardingDto;
import com.jotheca.onboarding.dto.OnboardingSubmitResponse;
import com.jotheca.onboarding.service.OnboardingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingSubmitResponse submit(@Valid @RequestBody CreateOnboardingRequest req) {
        return onboardingService.submit(req);
    }

    @GetMapping("/requests/mine")
    public List<OnboardingDto> mine() {
        return onboardingService.mine();
    }
}
