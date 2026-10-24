package com.example.demo.controller;

import com.example.demo.dto.forecast.NotificationDto;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{inn}/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final CompanyAccessService companyAccessService;
    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Notifications (alerts + recalculation journal) with the unread count for the current user")
    public ResponseEntity<NotificationDto.Response> list(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(notificationService.list(authentication.getName(), inn));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all current notifications as read")
    public ResponseEntity<NotificationDto.Response> readAll(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(notificationService.markAllRead(authentication.getName(), inn));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read (id as returned by the list, e.g. alert:gap:2026-05-01)")
    public ResponseEntity<NotificationDto.Response> read(@PathVariable String inn, @PathVariable String id,
                                                         Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(notificationService.markRead(authentication.getName(), inn, id));
    }
}
