package com.inttelgo.mspse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class PaymentDescriptionTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @Test
    void fullDescriptionWhenItFits() {
        assertEquals("Factura noviembre 2026 - 2 servicios - Descuento 10% ($12.000) - 3 pagos adicionales",
                PaymentDescription.of(NOV, 2, 0, 10, new BigDecimal("12000"), 3, 100));
        assertEquals("Factura noviembre 2026 - 1 servicio",
                PaymentDescription.of(NOV, 1, 0, null, BigDecimal.ZERO, 0, 80));
        assertEquals("1 pago adicional", PaymentDescription.of(null, 0, 0, null, BigDecimal.ZERO, 1, 80));
    }

    @Test
    void countsServiceExtras() {
        assertEquals("Factura noviembre 2026 - 1 servicio - 2 extras - Descuento 10% ($6.000)",
                PaymentDescription.of(NOV, 1, 2, 10, new BigDecimal("6000"), 0, 80));
        assertEquals("Factura noviembre 2026 - 1 extra",
                PaymentDescription.of(NOV, 0, 1, null, BigDecimal.ZERO, 0, 80));
        assertEquals("Fact nov 2026 - 3 serv - 2 extras - Dcto 10% - 4 pago adic",
                PaymentDescription.of(NOV, 3, 2, 10, new BigDecimal("6000"), 4, 70));
    }

    @Test
    void dropsDetailToFitTheLimit() {
        // 84 caracteres con pesos: sin pesos cabe en 80.
        assertEquals("Factura septiembre 2026 - 12 servicios - Descuento 15% - 10 pagos adicionales",
                PaymentDescription.of(SEP, 12, 0, 15, new BigDecimal("1234567"), 10, 80));
        assertEquals("Fact sep 2026 - 12 serv - Dcto 15% - 10 pago adic",
                PaymentDescription.of(SEP, 12, 0, 15, new BigDecimal("1234567"), 10, 60));
        String tiny = PaymentDescription.of(SEP, 12, 0, 15, new BigDecimal("1234567"), 10, 20);
        assertEquals(20, tiny.length());
    }

    @Test
    void urlConceptIsTrimmed() {
        assertEquals("Traslado de", PaymentDescription.fit("  Traslado   de servicio ", 11));
        assertTrue(PaymentDescription.fit("x".repeat(200), 80).length() == 80);
    }

    @Test
    void discountMatchesMsBuildRounding() {
        assertEquals(new BigDecimal("54100"), PseService.discounted(new BigDecimal("60050.00"), 10));
        assertEquals(new BigDecimal("45000"), PseService.discounted(new BigDecimal("50000.00"), 10));
        assertEquals(new BigDecimal("50000.00"), PseService.discounted(new BigDecimal("50000.00"), null));
        assertEquals(0, BigDecimal.ZERO.compareTo(PseService.discounted(new BigDecimal("50000.00"), 100)));
    }
}
