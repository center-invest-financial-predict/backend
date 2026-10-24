package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** "This user has read this notification of this company". Notifications themselves are computed, not stored. */
@Entity
@Table(name = "notification_reads")
@Getter
@Setter
@NoArgsConstructor
public class NotificationRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "company_inn", length = 20, nullable = false)
    private String companyInn;

    @Column(name = "notif_key", length = 255, nullable = false)
    private String key;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    public NotificationRead(Long userId, String companyInn, String key) {
        this.userId = userId;
        this.companyInn = companyInn;
        this.key = key;
        this.readAt = LocalDateTime.now();
    }
}
