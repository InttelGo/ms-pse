package com.inttelgo.mspse.service;

import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.TransactionDtos;
import com.inttelgo.mspse.common.DynamicFilter;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.common.PageSlice;
import com.inttelgo.mspse.common.PagedQuery;
import com.inttelgo.mspse.domain.DetalleTransaccion;
import com.inttelgo.mspse.domain.EstadoTransaccion;
import com.inttelgo.mspse.domain.Transaccion;
import com.inttelgo.mspse.exception.NotFoundException;
import com.inttelgo.mspse.repository.DetalleTransaccionRepository;
import com.inttelgo.mspse.repository.TransaccionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultas de transacciones (antes {@code Transaccion.controller.js}). */
@Service
@Transactional(readOnly = true)
public class TransaccionService {

    private final TransaccionRepository repository;
    private final DetalleTransaccionRepository detailRepository;
    private final PagedQuery pagedQuery;

    public TransaccionService(TransaccionRepository repository, DetalleTransaccionRepository detailRepository, PagedQuery pagedQuery) {
        this.repository = repository;
        this.detailRepository = detailRepository;
        this.pagedQuery = pagedQuery;
    }

    public Page<TransactionDtos.Response> list(int skip, int limit, String sortBy, String sortOrder, List<Filter> filters) {
        PageSlice<Transaccion> page = pagedQuery.search(
                Transaccion.class,
                (builder, query, root) -> DynamicFilter.build(builder, query, root, filters),
                DynamicFilter.sortAttribute(Transaccion.class, sortBy),
                sortOrder,
                skip,
                limit);
        return new Page<>(page.items().stream().map(item -> toResponse(item, null)).toList(),
                page.total(), page.skip(), page.limit(), page.hasMore());
    }

    public TransactionDtos.Response get(Long id) {
        Transaccion transaction = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("La transacción no existe"));
        return toResponse(transaction, detailRepository.findByTransaction_IdOrderByIdAsc(id));
    }

    public TransactionDtos.Response byTicket(String ticket) {
        Transaccion transaction = repository.findByNumberTicket(ticket)
                .orElseThrow(() -> new NotFoundException("Transacción no encontrada"));
        return toResponse(transaction, detailRepository.findByTransaction_IdOrderByIdAsc(transaction.getId()));
    }

    public Long idByTicket(String ticket) {
        return repository.findByNumberTicket(ticket)
                .map(Transaccion::getId)
                .orElseThrow(() -> new NotFoundException("Transacción no encontrada"));
    }

    static TransactionDtos.Response toResponse(Transaccion t, List<DetalleTransaccion> details) {
        EstadoTransaccion status = t.getStatus();
        return new TransactionDtos.Response(
                t.getId(),
                t.getNumberTicket(),
                t.getCodeTrazability(),
                t.getTransactionValue(),
                t.getDiscount(),
                t.getIvaValue(),
                t.getBankCode(),
                t.getBankName(),
                t.getTypeDocument(),
                t.getIdentification(),
                t.getFullName(),
                t.getEmail(),
                t.getPhone(),
                t.getCreateAt(),
                t.getCreateBy(),
                t.getResponseAt(),
                t.getUpdateAt(),
                t.getFirstReference(),
                t.getSecondReference(),
                t.getThirthReference(),
                t.getEntitycode(),
                t.getFinancialInstitutionCode(),
                t.getServiceCode(),
                t.getBankProcessDate(),
                t.getTrasnsactionCycle(),
                t.getPaymentDescription(),
                t.getAddress(),
                t.getUrlGenerated() == null ? null : t.getUrlGenerated().getId(),
                status == null ? null : status.getId(),
                CatalogSupport.namedItem(status),
                t.getBuildId(),
                t.getCollectionBuildId(),
                details == null ? null : details.stream().map(TransaccionService::toDetail).toList());
    }

    private static TransactionDtos.Detail toDetail(DetalleTransaccion d) {
        return new TransactionDtos.Detail(
                d.getId(),
                d.getDescription(),
                d.getInternetValue(),
                d.getTelephonyValue(),
                d.getTelevisionValue(),
                d.getValue(),
                d.getDiscount(),
                d.getServiceId(),
                d.getExtraId(),
                d.getPaymentId(),
                d.getCollectionBuildId());
    }
}
