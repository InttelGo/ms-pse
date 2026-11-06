package com.inttelgo.mspse.service;

import com.inttelgo.mspse.config.AppProperties;
import com.inttelgo.mspse.exception.BadRequestException;
import com.inttelgo.mspse.exception.NotFoundException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Mes de facturación ({@code build_at}) según el estado de los servicios, con las mismas categorías que
 * valida ms-build: <em>Pago anticipado</em> → mes siguiente, <em>Pagos normales</em> → mes actual,
 * <em>Mora</em> → mes anterior. Una factura tiene un solo mes, así que todos los servicios deben caer en el mismo.
 */
@Component
public class BillingPeriod {

    /** Ids fijos de las categorías en ms-build ({@code CategoriaMovimientoService}). */
    static final long PAGOS_NORMALES = 1L;
    static final long PAGO_ANTICIPADO = 2L;
    static final long MORA = 3L;

    private final BuildClient buildClient;
    private final ServiceLookup serviceLookup;
    private final ZoneId zone;

    public BillingPeriod(BuildClient buildClient, ServiceLookup serviceLookup, AppProperties properties) {
        this.buildClient = buildClient;
        this.serviceLookup = serviceLookup;
        this.zone = ZoneId.of(properties.zone());
    }

    /** Cliente al que se factura y primer día del mes a facturar. */
    public record Target(Long clientId, LocalDate buildAt) {
    }

    /**
     * Valida que los servicios existan, sean de un mismo cliente y paguen un mismo periodo. Se usa al
     * iniciar el pago (para rechazar antes de ir a PSE) y al aprobarse (con el estado de ese momento).
     */
    public Target resolve(Collection<Long> serviceIds) {
        Map<Long, ServiceLookup.ServiceInfo> services = serviceLookup.byIds(serviceIds);
        Set<Long> owners = new HashSet<>();
        List<Long> statuses = new java.util.ArrayList<>();
        for (Long serviceId : serviceIds) {
            ServiceLookup.ServiceInfo service = services.get(serviceId);
            if (service == null) {
                throw new NotFoundException("Servicio " + serviceId + " no existe");
            }
            owners.add(service.clientId());
            statuses.add(service.statusId());
        }
        if (owners.contains(null) || owners.size() != 1) {
            throw new BadRequestException("Los servicios de una transacción deben ser del mismo cliente");
        }
        return new Target(owners.iterator().next(), buildAt(statuses));
    }

    /** Primer día del mes a facturar; 400 si los estados no caen en un mismo periodo. */
    public LocalDate buildAt(Collection<Long> statusIds) {
        List<Long> advance = buildClient.categoryStatuses(PAGO_ANTICIPADO);
        List<Long> normal = buildClient.categoryStatuses(PAGOS_NORMALES);
        List<Long> arrears = buildClient.categoryStatuses(MORA);
        YearMonth current = YearMonth.now(zone);
        Set<YearMonth> months = new HashSet<>();
        for (Long status : statusIds) {
            if (status != null && advance.contains(status)) {
                months.add(current.plusMonths(1));
            } else if (status != null && normal.contains(status)) {
                months.add(current);
            } else if (status != null && arrears.contains(status)) {
                months.add(current.minusMonths(1));
            } else {
                throw new BadRequestException("El servicio está en un estado (" + status + ") que no se puede pagar por PSE");
            }
        }
        if (months.size() != 1) {
            throw new BadRequestException("Los servicios de una transacción deben pagar el mismo periodo (anticipado, actual o mora)");
        }
        return months.iterator().next().atDay(1);
    }
}
