package com.inttelgo.mspse.service;

import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.SelectItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusItem;
import com.inttelgo.mspse.api.v1.dto.CatalogDtos.StatusRequest;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.common.PageSlice;
import com.inttelgo.mspse.config.CurrentActor;
import com.inttelgo.mspse.domain.EstadoTransaccion;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.exception.ConflictException;
import com.inttelgo.mspse.repository.EstadoTransaccionRepository;
import com.inttelgo.mspse.repository.TransaccionRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EstadoTransaccionService {

    private final EstadoTransaccionRepository repository;
    private final TransaccionRepository transactionRepository;
    private final CatalogSupport catalogSupport;
    private final CurrentActor currentActor;

    public EstadoTransaccionService(
            EstadoTransaccionRepository repository,
            TransaccionRepository transactionRepository,
            CatalogSupport catalogSupport,
            CurrentActor currentActor) {
        this.repository = repository;
        this.transactionRepository = transactionRepository;
        this.catalogSupport = catalogSupport;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public Page<StatusItem> list(int skip, int limit, String sortBy, String sortOrder, List<Filter> filters) {
        PageSlice<EstadoTransaccion> page = catalogSupport.searchActive(EstadoTransaccion.class, skip, limit, sortBy, sortOrder, filters);
        return new Page<>(page.items().stream().map(EstadoTransaccionService::toItem).toList(),
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
            throw new ConflictException("El estado de transacción ya existe");
        }
        EstadoTransaccion item = new EstadoTransaccion();
        item.setStatus(status);
        catalogSupport.stampCreate(item, currentActor.requireUserId());
        return toItem(repository.save(item));
    }

    public StatusItem update(Long id, StatusRequest request) {
        EstadoTransaccion item = require(id);
        if (isSystem(id)) {
            throw new ConflictException("Los estados del sistema no se pueden modificar");
        }
        String status = normalize(request.status());
        if (repository.existsByStatusIgnoreCaseAndDeleteAtIsNullAndIdNot(status, id)) {
            throw new ConflictException("El estado de transacción ya existe");
        }
        item.setStatus(status);
        catalogSupport.stampUpdate(item, currentActor.requireUserId());
        return toItem(repository.save(item));
    }

    public void delete(Long id) {
        EstadoTransaccion item = require(id);
        if (isSystem(id)) {
            throw new ConflictException("Los estados del sistema no se pueden eliminar");
        }
        if (transactionRepository.existsByStatus_Id(id)) {
            throw new ConflictException("El estado de transacción está en uso");
        }
        catalogSupport.stampDelete(item, currentActor.requireUserId());
        repository.save(item);
    }

    public EstadoTransaccion require(Long id) {
        return catalogSupport.require(repository, id, "El estado de transacción no encontrado");
    }

    public EstadoTransaccion require(TransactionStatus status) {
        return require(status.id());
    }

    private static boolean isSystem(Long id) {
        for (TransactionStatus status : TransactionStatus.values()) {
            if (status.id() == id) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String status) {
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static StatusItem toItem(EstadoTransaccion item) {
        return new StatusItem(item.getId(), item.getStatus(), item.getCreateAt(), item.getCreateBy(),
                item.getUpdateAt(), item.getUpdateBy());
    }
}
