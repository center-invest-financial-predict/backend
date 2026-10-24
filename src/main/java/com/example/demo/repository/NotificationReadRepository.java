package com.example.demo.repository;

import com.example.demo.entity.NotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, Long> {

    @Query("SELECT n.key FROM NotificationRead n WHERE n.userId = :userId AND n.companyInn = :inn")
    Set<String> findKeys(@Param("userId") Long userId, @Param("inn") String inn);
}
