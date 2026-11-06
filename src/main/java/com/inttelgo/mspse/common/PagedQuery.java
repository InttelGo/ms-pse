package com.inttelgo.mspse.common;

import com.inttelgo.mspse.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

@Component
public class PagedQuery {

    private final EntityManager entityManager;

    public PagedQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @FunctionalInterface
    public interface Where<T> {
        Predicate build(CriteriaBuilder builder, CriteriaQuery<?> query, Root<T> root);
    }

    public <T> PageSlice<T> search(
            Class<T> type,
            Where<T> where,
            String sortBy,
            String sortOrder,
            int skip,
            int limit) {
        return search(type, where, sortBy, sortOrder, skip, limit, "id");
    }

    public <T> PageSlice<T> search(
            Class<T> type,
            Where<T> where,
            String sortBy,
            String sortOrder,
            int skip,
            int limit,
            String defaultSort) {
        if (sortOrder != null && !sortOrder.equalsIgnoreCase("asc") && !sortOrder.equalsIgnoreCase("desc")) {
            throw new BadRequestException("sort_order debe ser asc o desc");
        }
        int safeSkip = Math.max(skip, 0);
        int safeLimit = Math.min(Math.max(limit, 1), 1000);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(type);
        Root<T> root = query.from(type);
        Predicate predicate = where.build(builder, query, root);
        if (predicate != null) {
            query.where(predicate);
        }
        query.orderBy(order(builder, root, sortBy == null || sortBy.isBlank() ? defaultSort : sortBy, sortOrder));

        TypedQuery<T> typed = entityManager.createQuery(query);
        typed.setFirstResult(safeSkip);
        typed.setMaxResults(safeLimit);

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(type);
        countQuery.select(builder.countDistinct(countRoot));
        Predicate countPredicate = where.build(builder, countQuery, countRoot);
        if (countPredicate != null) {
            countQuery.where(countPredicate);
        }
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageSlice<>(typed.getResultList(), total, safeSkip, safeLimit);
    }

    private Order order(CriteriaBuilder builder, Root<?> root, String sortBy, String sortOrder) {
        Path<?> path;
        try {
            path = root.get(sortBy);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Columna de ordenamiento no permitida: " + sortBy);
        }
        if (sortOrder != null && sortOrder.equalsIgnoreCase("desc")) {
            return builder.desc(path);
        }
        return builder.asc(path);
    }
}
