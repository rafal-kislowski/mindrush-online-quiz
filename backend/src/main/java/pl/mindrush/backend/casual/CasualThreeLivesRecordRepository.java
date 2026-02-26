package pl.mindrush.backend.casual;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CasualThreeLivesRecordRepository extends JpaRepository<CasualThreeLivesRecord, Long> {
    Optional<CasualThreeLivesRecord> findByParticipantKey(String participantKey);
}

