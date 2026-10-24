package com.example.demo.controller;

import com.example.demo.dto.forecast.CompanyDto;
import com.example.demo.dto.forecast.CompanyOnboardingDto;
import com.example.demo.dto.forecast.CompanyProfileDto;
import com.example.demo.service.CompanyOnboardingService;
import jakarta.validation.Valid;
import com.example.demo.service.CompanyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyQueryService companyQueryService;
    private final CompanyOnboardingService companyOnboardingService;

    @GetMapping
    @Operation(summary = "List companies linked to current user")
    public ResponseEntity<List<CompanyDto>> list(Authentication authentication) {
        return ResponseEntity.ok(companyQueryService.listForUser(authentication.getName()));
    }

    @PostMapping
    @Operation(summary = "Create a company the system does not know yet, link it to the current user and choose "
            + "its data sources (e.g. only csv, then upload the file to /operations/csv). 409 if the INN exists.")
    public ResponseEntity<CompanyOnboardingDto.Response> create(
            @Valid @RequestBody CompanyOnboardingDto.CreateRequest request, Authentication authentication) {
        return ResponseEntity.ok(companyOnboardingService.create(authentication.getName(), request));
    }

    @GetMapping("/lookup")
    @Operation(summary = "Lookup company by INN (onboarding). Stub name if unknown.")
    public ResponseEntity<CompanyDto> lookup(@RequestParam String inn) {
        return ResponseEntity.ok(companyQueryService.lookup(inn));
    }

    @GetMapping("/{inn}")
    @Operation(summary = "Company card for an owned INN")
    public ResponseEntity<CompanyDto> get(@PathVariable String inn, Authentication authentication) {
        return ResponseEntity.ok(companyQueryService.getOwned(authentication.getName(), inn));
    }

    @PatchMapping("/{inn}")
    @Operation(summary = "Edit the company profile (name, legalForm, industry) of an owned INN")
    public ResponseEntity<CompanyDto> update(@PathVariable String inn,
                                             @Valid @RequestBody CompanyProfileDto.UpdateRequest request,
                                             Authentication authentication) {
        return ResponseEntity.ok(companyQueryService.update(authentication.getName(), inn, request));
    }

    @GetMapping("/{inn}/members")
    @Operation(summary = "Users who have this company linked (read-only)")
    public ResponseEntity<List<CompanyProfileDto.Member>> members(@PathVariable String inn,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(companyQueryService.members(authentication.getName(), inn));
    }
}
