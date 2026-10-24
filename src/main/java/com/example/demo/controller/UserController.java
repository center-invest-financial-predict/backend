package com.example.demo.controller;

import com.example.demo.dto.user.InnsRequest;
import com.example.demo.entity.User;
import com.example.demo.security.CustomUserDetailsService;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.IntegrationSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final CustomUserDetailsService userDetailsService;
    private final CompanyAccessService companyAccessService;
    private final IntegrationSyncService integrationSyncService;

    /** Задать (заменить) список ИНН текущего пользователя. */
    @PutMapping("/inns")
    public ResponseEntity<User> setInns(@Valid @RequestBody InnsRequest request, Authentication authentication) {
        companyAccessService.ensureCompaniesExist(request.getInns());

        User current = (User) userDetailsService.loadUserByUsername(authentication.getName());
        Set<String> previous = current.getInns() == null ? Set.of() : Set.copyOf(current.getInns());

        User user = userDetailsService.replaceInns(authentication.getName(), request.getInns());

        Set<String> newlyAdded = new LinkedHashSet<>(user.getInns());
        newlyAdded.removeAll(previous);
        if (!newlyAdded.isEmpty()) {
            integrationSyncService.syncInnsAsync(newlyAdded);
        }

        return ResponseEntity.ok(user);
    }

    @GetMapping("/inns")
    public ResponseEntity<Set<String>> getInns(Authentication authentication) {
        User user = (User) userDetailsService.loadUserByUsername(authentication.getName());
        return ResponseEntity.ok(user.getInns());
    }
}
