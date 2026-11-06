package com.inttelgo.mspse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.inttelgo.mspse.api.v1.dto.TransactionDtos;
import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.domain.DetalleTransaccion;
import com.inttelgo.mspse.domain.GenerateUrl;
import com.inttelgo.mspse.domain.Transaccion;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.exception.ApiException;
import com.inttelgo.mspse.exception.BadRequestException;
import com.inttelgo.mspse.exception.NotFoundException;
import com.inttelgo.mspse.repository.TransaccionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Flujo de pago PSE (antes {@code PSE.controller.js} + {@code pse-microservice.php}): bancos, inicio de
 * la transacción, consulta de estado, finalización y conciliación con ms-build al aprobarse.
 */
@Service
public class PseService {

    private static final Logger log = LoggerFactory.getLogger(PseService.class);
    private static final DateTimeFormatter SOLICITE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final PseClient pseClient;
    private final RecaptchaClient recaptcha;
    private final IdCipher idCipher;
    private final GenerateUrlService urlService;
    private final TransaccionService transactionService;
    private final TransactionLifecycle lifecycle;
    private final TransactionSettlement settlement;
    private final TransaccionRepository repository;
    private final BillingPeriod billingPeriod;
    private final ServiceLookup serviceLookup;
    private final BuildClient buildClient;
    private final AppProperties.Pse pse;
    private final Long systemUserId;
    private final ZoneId zone;

    public PseService(
            PseClient pseClient,
            RecaptchaClient recaptcha,
            IdCipher idCipher,
            GenerateUrlService urlService,
            TransaccionService transactionService,
            TransactionLifecycle lifecycle,
            TransactionSettlement settlement,
            TransaccionRepository repository,
            BillingPeriod billingPeriod,
            ServiceLookup serviceLookup,
            BuildClient buildClient,
            AppProperties properties) {
        this.pseClient = pseClient;
        this.recaptcha = recaptcha;
        this.idCipher = idCipher;
        this.urlService = urlService;
        this.transactionService = transactionService;
        this.lifecycle = lifecycle;
        this.settlement = settlement;
        this.repository = repository;
        this.billingPeriod = billingPeriod;
        this.serviceLookup = serviceLookup;
        this.buildClient = buildClient;
        this.pse = properties.pse();
        this.systemUserId = properties.build().systemUserId();
        this.zone = ZoneId.of(properties.zone());
    }

    public JsonNode banks() {
        return pseClient.banks();
    }

    public TransactionDtos.Started start(TransactionDtos.Create request) {
        recaptcha.verify(request.recaptchaToken());
        GenerateUrl url = request.urlGenerated() == null || request.urlGenerated().isBlank()
                ? null
                : urlService.requirePayable(request.urlGenerated());
        if (url != null && ((request.details() != null && !request.details().isEmpty()) || request.discount() != null)) {
            throw new BadRequestException("Un pago por URL no lleva líneas ni descuento: el valor sale de la URL");
        }
        Lines prepared = url == null ? lines(request.details(), request.discount()) : null;
        List<DetalleTransaccion> details = prepared == null ? List.of() : prepared.details();
        BigDecimal value = url != null
                ? url.getValue()
                : details.stream().map(DetalleTransaccion::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (value.signum() <= 0) {
            throw new BadRequestException("El valor a pagar debe ser mayor a cero");
        }
        String description = url != null
                ? PaymentDescription.fit(url.getConcept(), pse.descriptionMaxLength())
                : PaymentDescription.of(prepared.month(), prepared.services(), prepared.extras(), request.discount(),
                        prepared.discount(), prepared.collections(), pse.descriptionMaxLength());

        String ticket = newTicket();
        Instant now = Instant.now();
        Transaccion transaction = new Transaccion();
        transaction.setNumberTicket(ticket);
        transaction.setTransactionValue(CatalogSupport.money(value));
        transaction.setDiscount(request.discount());
        transaction.setIvaValue(CatalogSupport.money(BigDecimal.ZERO));
        transaction.setBankCode(request.financialInstitutionCode().trim());
        transaction.setBankName(request.bankName());
        transaction.setTypeDocument(request.typeDocument().trim());
        transaction.setIdentification(request.identification().trim());
        transaction.setFullName(request.fullName().trim());
        transaction.setEmail(request.email().trim());
        transaction.setPhone(request.phone().trim());
        transaction.setAddress(request.address().trim());
        transaction.setPaymentDescription(description);
        transaction.setFirstReference(transaction.getPhone());
        transaction.setSecondReference(transaction.getIdentification());
        transaction.setThirthReference(ticket);
        transaction.setEntitycode(pse.entityCode());
        transaction.setServiceCode(pse.serviceCode());
        transaction.setUrlGenerated(url);
        transaction.setCreateAt(now);
        transaction.setCreateBy(systemUserId);
        transaction.setUpdateAt(now);
        Transaccion saved = lifecycle.createPending(transaction, details);

        String ticketHash = idCipher.encrypt(ticket);
        JsonNode response;
        try {
            response = pseClient.createTransaction(pseRequest(saved, request.userType(), ticketHash, now));
        } catch (RuntimeException exception) {
            lifecycle.markRejectedOnStart(saved.getId());
            throw exception;
        }
        String trazabilityCode = text(response, "trazabilityCode");
        String returnCode = text(response, "returnCode");
        if (trazabilityCode == null) {
            lifecycle.markRejectedOnStart(saved.getId());
            log.warn("PSE no creó la transacción {} (ticket {}): returnCode={}", saved.getId(), ticket, returnCode);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PSE_TRANSACTION_REJECTED",
                    "PSE no aceptó la transacción" + (returnCode == null ? "" : " (" + returnCode + ")"));
        }
        String cycle = text(response, "transactionCycle");
        lifecycle.markStarted(saved.getId(), trazabilityCode, cycle);
        return new TransactionDtos.Started(ticket, ticketHash, trazabilityCode, text(response, "pseURL"), cycle, returnCode);
    }

    /** Consulta del landing al volver de PSE: {@code hash} es el ticket cifrado del {@code entityurl}. */
    public TransactionDtos.Response verify(String hash) {
        Long id = transactionService.idByTicket(idCipher.decrypt(hash));
        try {
            refresh(id);
        } catch (ApiException exception) {
            // Sin respuesta de PSE se devuelve el estado guardado; el job vuelve a consultar.
            log.warn("No se pudo actualizar la transacción {} con PSE: {}", id, exception.getMessage());
        }
        return transactionService.get(id);
    }

    /**
     * Consulta PSE si está pendiente, aplica el estado y, si quedó aprobada, crea en ms-build lo que falte.
     * Devuelve {@code true} si PSE respondió (aunque siga pendiente).
     */
    public boolean refresh(Long id) {
        Transaccion transaction = repository.findById(id).orElseThrow(() -> new NotFoundException("La transacción no existe"));
        boolean answered = false;
        if (TransactionStatus.PENDIENTE.is(transaction.getStatus()) && transaction.getCodeTrazability() != null) {
            JsonNode info = pseClient.transactionInfo(transaction.getCodeTrazability());
            answered = true;
            if (lifecycle.applyPseState(id, info)) {
                finalizeQuietly(transaction.getCodeTrazability());
            }
        }
        settleQuietly(id);
        return answered;
    }

    public TransactionSettlement.Result settle(Long id) {
        return settlement.settle(id);
    }

    private void settleQuietly(Long id) {
        try {
            TransactionSettlement.Result result = settlement.settle(id);
            if (!result.errors().isEmpty() && !result.errors().contains("La transacción no está aprobada")) {
                log.warn("Transacción {} aprobada con pendientes en ms-build: {}", id, result.errors());
            }
        } catch (RuntimeException exception) {
            log.error("No se pudo conciliar la transacción {} con ms-build", id, exception);
        }
    }

    private void finalizeQuietly(String trazabilityCode) {
        try {
            pseClient.finalizeTransaction(trazabilityCode);
        } catch (RuntimeException exception) {
            log.warn("La transacción {} no pudo ser finalizada en PSE: {}", trazabilityCode, exception.getMessage());
        }
    }

    /** Líneas listas para guardar, con lo que necesita la descripción del pago. */
    private record Lines(List<DetalleTransaccion> details, YearMonth month, int services, int extras, int collections,
            BigDecimal discount) {
    }

    private Lines lines(List<TransactionDtos.DetailLine> items, Integer percent) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("Se requiere url_generated o al menos una línea en details");
        }
        Set<Long> services = new HashSet<>();
        Set<Long> owners = new HashSet<>();
        Set<String> extras = new HashSet<>();
        Set<Long> collections = new HashSet<>();
        Map<Long, Map<Long, ServiceLookup.ExtraInfo>> linked = new HashMap<>();
        List<DetalleTransaccion> lines = new ArrayList<>();
        BigDecimal discounted = BigDecimal.ZERO;
        for (TransactionDtos.DetailLine item : items) {
            boolean service = item.serviceId() != null;
            boolean extra = item.extraId() != null;
            boolean collection = item.collectionBuildId() != null;
            if (extra && !service) {
                throw new BadRequestException("Un extra va con el service_id al que pertenece");
            }
            if (service == collection) {
                throw new BadRequestException("Cada línea lleva service_id o collection_build_id (uno de los dos)");
            }
            if (service && !extra && !services.add(item.serviceId())) {
                throw new BadRequestException("El servicio " + item.serviceId() + " está dos veces en la transacción");
            }
            if (extra && !extras.add(item.serviceId() + ":" + item.extraId())) {
                throw new BadRequestException("El extra " + item.extraId() + " del servicio " + item.serviceId() + " está dos veces en la transacción");
            }
            if (collection && !collections.add(item.collectionBuildId())) {
                throw new BadRequestException("El cobro " + item.collectionBuildId() + " está dos veces en la transacción");
            }
            String description = item.description() == null || item.description().isBlank() ? null : item.description().trim();
            if (extra) {
                ServiceLookup.ExtraInfo info = linked.computeIfAbsent(item.serviceId(), serviceLookup::extras).get(item.extraId());
                if (info == null) {
                    throw new BadRequestException("El extra " + item.extraId() + " no está asociado al servicio " + item.serviceId());
                }
                if (description == null) {
                    description = info.name();
                }
            }
            if (service) {
                owners.add(item.serviceId());
            }
            BigDecimal base = CatalogSupport.money(item.value());
            // Como en ms-build, el % solo aplica a la línea del plan del servicio (el extra va sin servicio).
            BigDecimal net = service && !extra ? discounted(base, percent) : base;
            BigDecimal discount = base.subtract(net).max(BigDecimal.ZERO);
            discounted = discounted.add(discount);
            DetalleTransaccion line = new DetalleTransaccion();
            line.setDescription(description);
            line.setServiceId(item.serviceId());
            line.setExtraId(item.extraId());
            line.setCollectionBuildId(item.collectionBuildId());
            line.setInternetValue(CatalogSupport.money(item.internetValue()));
            line.setTelephonyValue(CatalogSupport.money(item.telephonyValue()));
            line.setTelevisionValue(CatalogSupport.money(item.televisionValue()));
            line.setValue(CatalogSupport.money(net));
            line.setDiscount(discount.signum() > 0 ? CatalogSupport.money(discount) : null);
            lines.add(line);
        }
        if (percent != null && services.isEmpty()) {
            throw new BadRequestException("El descuento solo aplica a pagos de factura (líneas con service_id)");
        }
        YearMonth month = owners.isEmpty() ? null : YearMonth.from(billingPeriod.resolve(owners).buildAt());
        for (Long collectionId : collections) {
            BuildClient.Collection found = buildClient.collection(collectionId);
            if (found.nextQuota() > found.quotas()) {
                throw new BadRequestException("El cobro " + collectionId + " ya tiene todas sus cuotas pagadas");
            }
        }
        return new Lines(lines, month, services.size(), extras.size(), collections.size(), discounted);
    }

    /**
     * Mismo cálculo que ms-build ({@code FacturaService.line}): resta el % con 2 decimales y redondea hacia
     * arriba a la centena. Así lo cobrado en PSE es igual al valor de la factura.
     */
    static BigDecimal discounted(BigDecimal base, Integer percent) {
        if (percent == null || percent <= 0) {
            return base;
        }
        BigDecimal pct = BigDecimal.valueOf(percent);
        BigDecimal value = base.subtract(base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        return value.divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).multiply(BigDecimal.valueOf(100));
    }

    private Map<String, Object> pseRequest(Transaccion t, Integer userType, String ticketHash, Instant now) {
        String entityUrl = pse.entityUrl() == null ? "" : pse.entityUrl();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("entityCode", pse.entityCode());
        request.put("financialInstitutionCode", t.getBankCode());
        request.put("serviceCode", pse.serviceCode());
        request.put("transactionValue", t.getTransactionValue().stripTrailingZeros().toPlainString());
        request.put("vatValue", "0");
        request.put("ticketId", t.getNumberTicket());
        request.put("entityurl", entityUrl + ticketHash);
        request.put("userType", String.valueOf(userType));
        request.put("referenceNumber1", t.getFirstReference());
        request.put("referenceNumber2", t.getSecondReference());
        request.put("referenceNumber3", t.getThirthReference());
        request.put("soliciteDate", ZonedDateTime.ofInstant(now, zone).format(SOLICITE_DATE));
        request.put("paymentDescription", t.getPaymentDescription());
        request.put("identificationType", t.getTypeDocument());
        request.put("identificationNumber", t.getIdentification());
        request.put("fullName", t.getFullName());
        request.put("cellphoneNumber", t.getPhone());
        request.put("address", t.getAddress());
        request.put("email", t.getEmail());
        request.put("beneficiaryIdentificationType", t.getTypeDocument());
        request.put("beneficiaryIdentification", t.getIdentification());
        request.put("beneficiaryEntityIdentificationType", pse.beneficiaryType());
        request.put("beneficiaryEntityIdentification", pse.beneficiaryIdentify());
        request.put("beneficiaryEntityName", pse.beneficiaryName());
        request.put("beneficiaryEntityCIIUCategory", pse.beneficiaryCategory());
        request.put("indicator4per1000", 0);
        return request;
    }

    /** Mismo formato que usaba el landing ({@code Date.now()}), único en la tabla. */
    private String newTicket() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String ticket = String.valueOf(System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(0, 1000));
            if (!repository.existsByNumberTicket(ticket)) {
                return ticket;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "No se pudo generar el ticket, intente de nuevo");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
