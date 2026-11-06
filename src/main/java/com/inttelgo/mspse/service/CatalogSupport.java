package com.inttelgo.mspse.service;

import com.inttelgo.mspse.api.v1.dto.CatalogDtos.NamedItem;
import com.inttelgo.mspse.common.DynamicFilter;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.common.PageSlice;
import com.inttelgo.mspse.common.PagedQuery;
import com.inttelgo.mspse.domain.Auditable;
import com.inttelgo.mspse.domain.EstadoTransaccion;
import com.inttelgo.mspse.domain.EstadoUrl;
import com.inttelgo.mspse.exception.NotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public class CatalogSupport {

    private final PagedQuery pagedQuery;

    public CatalogSupport(PagedQuery pagedQuery) {
        this.pagedQuery = pagedQuery;
    }

    /** Búsqueda paginada que excluye los registros con baja lógica. */
    public <T extends Auditable> PageSlice<T> searchActive(
            Class<T> type, int skip, int limit, String sortBy, String sortOrder, List<Filter> filters) {
        return pagedQuery.search(
                type,
                (builder, query, root) -> {
                    Predicate active = builder.isNull(root.get("deleteAt"));
                    Predicate extra = DynamicFilter.build(builder, query, root, filters);
                    return extra == null ? active : builder.and(active, extra);
                },
                DynamicFilter.sortAttribute(type, sortBy),
                sortOrder,
                skip,
                limit);
    }

    public <T extends Auditable, K> T require(JpaRepository<T, K> repository, K id, String message) {
        return repository.findById(id)
                .filter(item -> item.getDeleteAt() == null)
                .orElseThrow(() -> new NotFoundException(message));
    }

    public void stampCreate(Auditable entity, Long actorId) {
        Instant now = Instant.now();
        entity.setCreateAt(now);
        entity.setUpdateAt(now);
        entity.setCreateBy(actorId);
        entity.setUpdateBy(actorId);
    }

    public void stampUpdate(Auditable entity, Long actorId) {
        entity.setUpdateAt(Instant.now());
        entity.setUpdateBy(actorId);
    }

    public void stampDelete(Auditable entity, Long actorId) {
        Instant now = Instant.now();
        entity.setDeleteAt(now);
        entity.setUpdateAt(now);
        entity.setUpdateBy(actorId);
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static NamedItem namedItem(EstadoTransaccion status) {
        return status == null ? null : new NamedItem(status.getId(), status.getStatus());
    }

    public static NamedItem namedItem(EstadoUrl status) {
        return status == null ? null : new NamedItem(status.getId(), status.getStatus());
    }
}
