package com.example.demo.controller;

import com.example.demo.dto.forecast.CompanyDto;
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

    @GetMapping
    @Operation(summary = "List companies linked to current user")
    public ResponseEntity<List<CompanyDto>> list(Authentication authentication) {
        return ResponseEntity.ok(companyQueryService.listForUser(authentication.getName()));
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
}
