package com.inttelgo.mspse.service;

import com.inttelgo.mspse.api.v1.dto.CatalogDtos.Page;
import com.inttelgo.mspse.api.v1.dto.GenerateUrlDtos;
import com.inttelgo.mspse.common.Filter;
import com.inttelgo.mspse.common.PageSlice;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.config.CurrentActor;
import com.inttelgo.mspse.config.HttpClients;
import com.inttelgo.mspse.domain.EstadoUrl;
import com.inttelgo.mspse.domain.GenerateUrl;
import com.inttelgo.mspse.domain.UrlStatus;
import com.inttelgo.mspse.exception.BadRequestException;
import com.inttelgo.mspse.exception.ConflictException;
import com.inttelgo.mspse.exception.NotFoundException;
import com.inttelgo.mspse.repository.GenerateUrlRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * URLs de pago (antes {@code GenerateURL.controller.js}). El estado sale de su propio catálogo
 * ({@link UrlStatus}): ACTIVA, PAGADA o DESHABILITADA.
 */
@Service
@Transactional
public class GenerateUrlService {

    private final GenerateUrlRepository repository;
    private final EstadoUrlService statusService;
    private final CatalogSupport catalogSupport;
    private final CurrentActor currentActor;
    private final IdCipher idCipher;
    private final AppProperties properties;
    private final ZoneId zone;

    public GenerateUrlService(
            GenerateUrlRepository repository,
            EstadoUrlService statusService,
            CatalogSupport catalogSupport,
            CurrentActor currentActor,
            IdCipher idCipher,
            AppProperties properties) {
        this.repository = repository;
        this.statusService = statusService;
        this.catalogSupport = catalogSupport;
        this.currentActor = currentActor;
        this.idCipher = idCipher;
        this.properties = properties;
        this.zone = ZoneId.of(properties.zone());
    }

    @Transactional(readOnly = true)
    public Page<GenerateUrlDtos.Response> list(int skip, int limit, String sortBy, String sortOrder, List<Filter> filters) {
        PageSlice<GenerateUrl> page = catalogSupport.searchActive(GenerateUrl.class, skip, limit, sortBy, sortOrder, filters);
        return new Page<>(page.items().stream().map(this::toResponse).toList(),
                page.total(), page.skip(), page.limit(), page.hasMore());
    }

    @Transactional(readOnly = true)
    public GenerateUrlDtos.Response get(Long id) {
        return toResponse(require(id));
    }

    public GenerateUrlDtos.Response create(GenerateUrlDtos.Create request) {
        if (request.expiryAt().isBefore(today())) {
            throw new BadRequestException("La fecha de vencimiento no puede ser anterior a hoy");
        }
        GenerateUrl url = new GenerateUrl();
        url.setConcept(request.concept().trim());
        url.setValue(CatalogSupport.money(request.value()));
        url.setExpiryAt(request.expiryAt());
        url.setStatus(statusService.require(UrlStatus.ACTIVA));
        catalogSupport.stampCreate(url, currentActor.requireUserId());
        GenerateUrl saved = repository.save(url);
        saved.setUrl(link(saved.getId()));
        return toResponse(repository.save(saved));
    }

    public GenerateUrlDtos.Response update(Long id, GenerateUrlDtos.Update request) {
        GenerateUrl url = require(id);
        if (UrlStatus.PAGADA.is(url.getStatus())) {
            throw new ConflictException("La URL ya fue pagada y no se puede modificar");
        }
        if (request.concept() != null) {
            if (request.concept().isBlank()) {
                throw new BadRequestException("concept no puede estar vacío");
            }
            url.setConcept(request.concept().trim());
        }
        if (request.value() != null) {
            url.setValue(CatalogSupport.money(request.value()));
        }
        if (request.expiryAt() != null) {
            url.setExpiryAt(request.expiryAt());
        }
        if (request.statusId() != null) {
            if (request.statusId() != UrlStatus.ACTIVA.id() && request.statusId() != UrlStatus.DESHABILITADA.id()) {
                throw new BadRequestException("Una URL solo se puede activar (ACTIVA) o deshabilitar (DESHABILITADA)");
            }
            url.setStatus(statusService.require(request.statusId()));
        }
        catalogSupport.stampUpdate(url, currentActor.requireUserId());
        return toResponse(repository.save(url));
    }

    public void delete(Long id) {
        GenerateUrl url = require(id);
        if (UrlStatus.PAGADA.is(url.getStatus())) {
            throw new ConflictException("La URL ya fue pagada y no se puede eliminar");
        }
        catalogSupport.stampDelete(url, currentActor.requireUserId());
        repository.save(url);
    }

    /** Lo que muestra el landing al abrir {@code /pse?payment=<hash>}. */
    @Transactional(readOnly = true)
    public GenerateUrlDtos.Public publicView(String hash) {
        GenerateUrl url = requirePayable(hash);
        return new GenerateUrlDtos.Public(hash, url.getConcept(), url.getValue(), url.getExpiryAt());
    }

    /** URL activa, sin pagar y sin vencer; si no, 400 como en el backend Node. */
    @Transactional(readOnly = true)
    public GenerateUrl requirePayable(String hash) {
        Long id = idCipher.decryptId(hash);
        GenerateUrl url = repository.findById(id)
                .filter(found -> found.getDeleteAt() == null)
                .orElseThrow(() -> new NotFoundException("URL no válida"));
        if (!UrlStatus.ACTIVA.is(url.getStatus()) || isExpired(url)) {
            throw new BadRequestException("URL canjeada o superó el plazo");
        }
        return url;
    }

    /** Marca la URL como pagada. Se llama dentro de la transacción que aprueba el pago. */
    public void markPaid(GenerateUrl url, Long actorId) {
        EstadoUrl paid = statusService.require(UrlStatus.PAGADA);
        url.setStatus(paid);
        catalogSupport.stampUpdate(url, actorId);
        repository.save(url);
    }

    public GenerateUrl require(Long id) {
        return catalogSupport.require(repository, id, "URL no encontrada");
    }

    private boolean isExpired(GenerateUrl url) {
        return url.getExpiryAt() == null || url.getExpiryAt().isBefore(today());
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private String link(Long id) {
        String base = properties.landingUrl() == null ? "" : HttpClients.trimSlash(properties.landingUrl().trim());
        return base + "/pse?payment=" + idCipher.encrypt(String.valueOf(id));
    }

    private GenerateUrlDtos.Response toResponse(GenerateUrl url) {
        EstadoUrl status = url.getStatus();
        return new GenerateUrlDtos.Response(
                url.getId(),
                url.getUrl(),
                url.getConcept(),
                url.getValue(),
                url.getExpiryAt(),
                status == null ? null : status.getId(),
                CatalogSupport.namedItem(status),
                isExpired(url),
                url.getCreateAt(),
                url.getCreateBy(),
                url.getUpdateAt(),
                url.getUpdateBy());
    }
}
