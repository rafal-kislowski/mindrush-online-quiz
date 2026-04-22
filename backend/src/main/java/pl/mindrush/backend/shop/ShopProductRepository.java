package pl.mindrush.backend.shop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopProductRepository extends JpaRepository<ShopProduct, Long> {

    Optional<ShopProduct> findByCode(String code);

    Optional<ShopProduct> findBySlugAndStatus(String slug, ShopProductStatus status);

    List<ShopProduct> findAllByStatusOrderBySortOrderAscUpdatedAtDesc(ShopProductStatus status);

    List<ShopProduct> findAllByOrderByUpdatedAtDesc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);
}
