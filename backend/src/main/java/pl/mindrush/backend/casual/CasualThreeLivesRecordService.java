package pl.mindrush.backend.casual;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.mindrush.backend.casual.dto.CasualThreeLivesBestDto;
import pl.mindrush.backend.guest.GuestSession;
import pl.mindrush.backend.guest.GuestSessionRepository;
import pl.mindrush.backend.guest.GuestSessionService;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
public class CasualThreeLivesRecordService {

    private final Clock clock;
    private final GuestSessionService guestSessionService;
    private final GuestSessionRepository guestSessionRepository;
    private final CasualThreeLivesRecordRepository recordRepository;

    public CasualThreeLivesRecordService(
            Clock clock,
            GuestSessionService guestSessionService,
            GuestSessionRepository guestSessionRepository,
            CasualThreeLivesRecordRepository recordRepository
    ) {
        this.clock = clock;
        this.guestSessionService = guestSessionService;
        this.guestSessionRepository = guestSessionRepository;
        this.recordRepository = recordRepository;
    }

    @Transactional(readOnly = true)
    public Optional<CasualThreeLivesBestDto> findBestForRequest(HttpServletRequest request) {
        GuestSession session = guestSessionService.requireValidSession(request);
        return findBestForSession(session)
                .map(r -> new CasualThreeLivesBestDto(
                        r.getBestPoints(),
                        r.getBestAnswered(),
                        r.getBestDurationMs(),
                        r.getUpdatedAt().toString()
                ));
    }

    public void updateBestForGuestSession(
            String guestSessionId,
            int points,
            int answered,
            long durationMs
    ) {
        GuestSession session = guestSessionRepository.findById(guestSessionId).orElse(null);
        if (session == null) return;

        String participantKey = participantKey(session);
        Instant now = clock.instant();
        int normalizedPoints = Math.max(0, points);
        int normalizedAnswered = Math.max(0, answered);
        long normalizedDuration = Math.max(0L, durationMs);

        CasualThreeLivesRecord record = recordRepository.findByParticipantKey(participantKey).orElse(null);
        if (record == null) {
            CasualThreeLivesRecord created = CasualThreeLivesRecord.create(
                    participantKey,
                    session.getId(),
                    session.getUserId(),
                    normalizedPoints,
                    normalizedAnswered,
                    normalizedDuration,
                    now
            );
            try {
                recordRepository.saveAndFlush(created);
                return;
            } catch (DataIntegrityViolationException ex) {
                // Another concurrent transaction inserted the same participant row.
                record = recordRepository.findByParticipantKey(participantKey).orElse(null);
                if (record == null) {
                    throw ex;
                }
            }
        }

        record.setGuestSessionId(session.getId());
        record.setUserId(session.getUserId());

        if (!isBetterRun(
                normalizedPoints,
                normalizedAnswered,
                normalizedDuration,
                record.getBestPoints(),
                record.getBestAnswered(),
                record.getBestDurationMs()
        )) {
            recordRepository.save(record);
            return;
        }

        record.setBestPoints(normalizedPoints);
        record.setBestAnswered(normalizedAnswered);
        record.setBestDurationMs(normalizedDuration);
        record.setUpdatedAt(now);
        recordRepository.save(record);
    }

    private Optional<CasualThreeLivesRecord> findBestForSession(GuestSession session) {
        return recordRepository.findByParticipantKey(participantKey(session));
    }

    private String participantKey(GuestSession session) {
        if (session.getUserId() != null) {
            return "USER:" + session.getUserId();
        }
        return "GUEST:" + session.getId();
    }

    private boolean isBetterRun(
            int candidatePoints,
            int candidateAnswered,
            long candidateDurationMs,
            int currentBestPoints,
            int currentBestAnswered,
            long currentBestDurationMs
    ) {
        if (candidatePoints != currentBestPoints) {
            return candidatePoints > currentBestPoints;
        }
        if (candidateAnswered != currentBestAnswered) {
            return candidateAnswered > currentBestAnswered;
        }
        return candidateDurationMs < currentBestDurationMs;
    }
}
