package pl.mindrush.backend.achievement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAchievementUnlockRepository extends JpaRepository<UserAchievementUnlock, Long> {

    boolean existsByUserIdAndAchievementKey(Long userId, String achievementKey);

    List<UserAchievementUnlock> findAllByUserIdOrderByUnlockedAtDesc(Long userId);
}
