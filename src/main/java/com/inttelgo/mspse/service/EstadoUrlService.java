package com.inttelgo.mspse.service;

import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.SelectItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusRequest;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.common.PageSlice;
import com.inttelgo.mspse.config.CurrentActor;
import com.inttelgo.mspse.domain.EstadoUrl;
import com.inttelgo.mspse.domain.UrlStatus;
import com.inttelgo.mspse.exception.ConflictException;
import com.inttelgo.mspse.repository.EstadoUrlRepository;
import com.inttelgo.mspse.repository.GenerateUrlRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catálogo de estados de las URLs de pago. */
@Service
@Transactional
public class EstadoUrlService {

    private final EstadoUrlRepository repository;
    private final GenerateUrlRepository urlRepository;
    private final CatalogSupport catalogSupport;
    private final CurrentActor currentActor;

    public EstadoUrlService(
            EstadoUrlRepository repository,
            GenerateUrlRepository urlRepository,
            CatalogSupport catalogSupport,
            CurrentActor currentActor) {
        this.repository = repository;
        this.urlRepository = urlRepository;
        this.catalogSupport = catalogSupport;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public Page<StatusItem> list(int skip, int limit, String sortBy, String sortOrder, List<Filter> filters) {
        PageSlice<EstadoUrl> page = catalogSupport.searchActive(EstadoUrl.class, skip, limit, sortBy, sortOrder, filters);
        return new Page<>(page.items().stream().map(EstadoUrlService::toItem).toList(),
                page.total(), page.skip(), page.limit(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public List<SelectItem> select() {
        return repository.findByDeleteAtIsNullOrderByIdAsc().stream()
                .map(status -> new SelectItem(status.getId(), status.getStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public StatusItem get(Long id) {
        return toItem(require(id));
    }

    public StatusItem create(StatusRequest request) {
        String status = normalize(request.status());
        if (repository.existsByStatusIgnoreCaseAndDeleteAtIsNull(status)) {
            throw new ConflictException("El estado de URL ya existe");
        }
        EstadoUrl item = new EstadoUrl();
        item.setStatus(status);
        catalogSupport.stampCreate(item, currentActor.requireUserId());
        return toItem(repository.save(item));
    }

    public StatusItem update(Long id, StatusRequest request) {
        EstadoUrl item = require(id);
        if (UrlStatus.isSystem(id)) {
            throw new ConflictException("Los estados del sistema no se pueden modificar");
        }
        String status = normalize(request.status());
        if (repository.existsByStatusIgnoreCaseAndDeleteAtIsNullAndIdNot(status, id)) {
            throw new ConflictException("El estado de URL ya existe");
        }
        item.setStatus(status);
        catalogSupport.stampUpdate(item, currentActor.requireUserId());
        return toItem(repository.save(item));
    }

    public void delete(Long id) {
        EstadoUrl item = require(id);
        if (UrlStatus.isSystem(id)) {
            throw new ConflictException("Los estados del sistema no se pueden eliminar");
        }
        if (urlRepository.existsByStatus_Id(id)) {
            throw new ConflictException("El estado de URL está en uso");
        }
        catalogSupport.stampDelete(item, currentActor.requireUserId());
        repository.save(item);
    }

    public EstadoUrl require(Long id) {
        return catalogSupport.require(repository, id, "El estado de URL no encontrado");
    }

    public EstadoUrl require(UrlStatus status) {
        return require(status.id());
    }

    private static String normalize(String status) {
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static StatusItem toItem(EstadoUrl item) {
        return new StatusItem(item.getId(), item.getStatus(), item.getCreateAt(), item.getCreateBy(),
                item.getUpdateAt(), item.getUpdateBy());
    }
}
