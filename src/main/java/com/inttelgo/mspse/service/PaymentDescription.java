package com.inttelgo.mspse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arma el {@code paymentDescription} que se manda a PSE sin pasar del límite de caracteres
 * ({@code PSE_DESCRIPTION_MAX_LENGTH}). Prueba de la versión más completa a la más corta y se queda con
 * la primera que cabe, por ejemplo:
 * <pre>
 * Factura noviembre 2026 - 2 servicios - 1 extra - Descuento 10% ($8.000) - 1 pago adicional
 * Factura noviembre 2026 - 2 servicios - 1 extra - Descuento 10% - 1 pago adicional
 * Fact nov 2026 - 2 serv - 1 extra - Dcto 10% - 1 pago adic
 * </pre>
 */
public final class PaymentDescription {

    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private PaymentDescription() {
    }

    /**
     * @param month mes que se factura; nulo si no hay servicios
     * @param services cantidad de servicios facturados
     * @param extras cantidad de extras de servicio (p. ej. pantallas WIN+)
     * @param percent descuento en % (nulo sin descuento)
     * @param discount pesos descontados
     * @param additional cantidad de pagos adicionales (cuotas de cobros)
     */
    public static String of(YearMonth month, int services, int extras, Integer percent, BigDecimal discount, int additional, int maxLength) {
        boolean hasDiscount = percent != null && percent > 0;
        List<String> candidates = new ArrayList<>();
        candidates.add(join(
                month != null ? "Factura " + month.getMonth().getDisplayName(TextStyle.FULL, ES) + " " + month.getYear() : null,
                services > 0 ? plural(services, "servicio", "servicios") : null,
                extras > 0 ? plural(extras, "extra", "extras") : null,
                hasDiscount ? "Descuento " + percent + "% (" + pesos(discount) + ")" : null,
                additional > 0 ? plural(additional, "pago adicional", "pagos adicionales") : null));
        candidates.add(join(
                month != null ? "Factura " + month.getMonth().getDisplayName(TextStyle.FULL, ES) + " " + month.getYear() : null,
                services > 0 ? plural(services, "servicio", "servicios") : null,
                extras > 0 ? plural(extras, "extra", "extras") : null,
                hasDiscount ? "Descuento " + percent + "%" : null,
                additional > 0 ? plural(additional, "pago adicional", "pagos adicionales") : null));
        candidates.add(join(
                month != null ? "Fact " + shortMonth(month) + " " + month.getYear() : null,
                services > 0 ? services + " serv" : null,
                extras > 0 ? plural(extras, "extra", "extras") : null,
                hasDiscount ? "Dcto " + percent + "%" : null,
                additional > 0 ? additional + " pago adic" : null));
        for (String candidate : candidates) {
            if (candidate.length() <= maxLength) {
                return candidate;
            }
        }
        String last = candidates.get(candidates.size() - 1);
        return last.substring(0, Math.max(maxLength, 1));
    }

    /** Recorta un texto libre (concepto de URL) al límite de PSE. */
    public static String fit(String text, int maxLength) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String join(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                present.add(part);
            }
        }
        return String.join(" - ", present);
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static String shortMonth(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.SHORT, ES).replace(".", "");
        return name.length() > 3 ? name.substring(0, 3) : name;
    }

    private static String pesos(BigDecimal value) {
        long amount = (value == null ? BigDecimal.ZERO : value).setScale(0, RoundingMode.HALF_UP).longValue();
        return "$" + String.format(Locale.ROOT, "%,d", amount).replace(',', '.');
    }
}
