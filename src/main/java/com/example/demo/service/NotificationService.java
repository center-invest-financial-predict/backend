package com.example.demo.service;

import com.example.demo.dto.forecast.AlertDto;
import com.example.demo.dto.forecast.NotificationDto;
import com.example.demo.dto.forecast.RecalcJournalDto;
import com.example.demo.entity.NotificationRead;
import com.example.demo.entity.User;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.NotificationReadRepository;
import com.example.demo.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notifications for the header bell: current alerts plus the latest forecast recalculations, with a per-user read mark.
 * Alert ids contain the date they are about ("alert:gap:2026-05-01"), so a gap that moves is a new notification.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int JOURNAL_LIMIT = 10;
    private static final Map<String, Integer> SEVERITY_ORDER = Map.of("critical", 0, "warning", 1, "info", 2);

    private final CashForecastService cashForecastService;
    private final NotificationReadRepository notificationReadRepository;
    private final CustomUserDetailsService userDetailsService;

    @Transactional(readOnly = true)
    public NotificationDto.Response list(String email, String inn) {
        Long userId = userId(email);
        Set<String> read = notificationReadRepository.findKeys(userId, inn);
        List<NotificationDto> items = current(inn).stream()
                .map(n -> withRead(n, read.contains(n.id())))
                .toList();
        return new NotificationDto.Response(inn, items.stream().filter(n -> !n.read()).count(), items);
    }

    @Transactional
    public NotificationDto.Response markRead(String email, String inn, String id) {
        if (current(inn).stream().noneMatch(n -> n.id().equals(id))) {
            throw new ResourceNotFoundException("Notification not found: " + id);
        }
        Long userId = userId(email);
        if (!notificationReadRepository.findKeys(userId, inn).contains(id)) {
            notificationReadRepository.save(new NotificationRead(userId, inn, id));
        }
        return list(email, inn);
    }

    @Transactional
    public NotificationDto.Response markAllRead(String email, String inn) {
        Long userId = userId(email);
        Set<String> read = notificationReadRepository.findKeys(userId, inn);
        for (NotificationDto n : current(inn)) {
            if (!read.contains(n.id())) {
                notificationReadRepository.save(new NotificationRead(userId, inn, n.id()));
            }
        }
        return list(email, inn);
    }

    private List<NotificationDto> current(String inn) {
        List<NotificationDto> out = new ArrayList<>();
        List<AlertDto> alerts;
        try {
            alerts = cashForecastService.alerts(inn);
        } catch (InsufficientHistoryException e) {
            alerts = List.of();
        }
        alerts.stream()
                .sorted(Comparator.comparing(a -> SEVERITY_ORDER.getOrDefault(a.severity(), 3)))
                .forEach(a -> out.add(new NotificationDto(
                        "alert:" + a.id() + (a.date() == null ? "" : ":" + a.date()), "ALERT", a.type(), a.severity(),
                        a.title(), a.description(), a.date(), null, a.amount(), a.actionLabel(), a.actionTarget(),
                        false)));
        for (RecalcJournalDto j : cashForecastService.journal(inn, JOURNAL_LIMIT)) {
            out.add(new NotificationDto("journal:" + j.id(), "JOURNAL", "JOURNAL", "info",
                    "Прогноз пересчитан", j.message(), null, j.occurredAt(), null, "Открыть прогноз", "forecast",
                    false));
        }
        return out;
    }

    private static NotificationDto withRead(NotificationDto n, boolean read) {
        return new NotificationDto(n.id(), n.kind(), n.type(), n.severity(), n.title(), n.description(), n.date(),
                n.occurredAt(), n.amount(), n.actionLabel(), n.actionTarget(), read);
    }

    private Long userId(String email) {
        return ((User) userDetailsService.loadUserByUsername(email)).getId();
    }
}
