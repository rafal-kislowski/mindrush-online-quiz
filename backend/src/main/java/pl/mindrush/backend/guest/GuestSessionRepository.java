package pl.mindrush.backend.guest;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {
}

