package com.example.demo.repository;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Users who have this company linked (user_inns). */
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE :inn MEMBER OF u.inns ORDER BY u.id")
    java.util.List<User> findByLinkedInn(@org.springframework.data.repository.query.Param("inn") String inn);
}
