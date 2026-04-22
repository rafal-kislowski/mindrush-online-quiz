package pl.mindrush.backend.shop;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShopOrderRepository extends JpaRepository<ShopOrder, Long> {

    List<ShopOrder> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ShopOrder> findByPublicIdAndUserId(String publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o
            from ShopOrder o
            where o.publicId = :publicId
              and o.userId = :userId
            """)
    Optional<ShopOrder> findByPublicIdAndUserIdForUpdate(
            @Param("publicId") String publicId,
            @Param("userId") Long userId
    );
}
