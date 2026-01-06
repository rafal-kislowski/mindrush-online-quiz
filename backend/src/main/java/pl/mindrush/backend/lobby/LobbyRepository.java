package pl.mindrush.backend.lobby;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LobbyRepository extends JpaRepository<Lobby, String> {
    Optional<Lobby> findByCode(String code);
    boolean existsByCode(String code);
}

