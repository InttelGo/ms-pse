package com.inttelgo.mspse.domain;

import java.util.Locale;

/** Ids sembrados de {@link EstadoTransaccion}. */
public enum TransactionStatus {
    PENDIENTE(1L),
    APROBADA(2L),
    RECHAZADA(3L),
    FALLIDA(4L),
    CANCELADA(5L);

    private final long id;

    TransactionStatus(long id) {
        this.id = id;
    }

    public long id() {
        return id;
    }

    public boolean is(EstadoTransaccion status) {
        return status != null && status.getId() != null && status.getId() == id;
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** Estado local según {@code transactionState} de PSE; nulo si PSE no devolvió un estado conocido. */
    public static TransactionStatus fromPse(String state) {
        if (state == null) {
            return null;
        }
        return switch (state.trim().toUpperCase(Locale.ROOT)) {
            case "OK" -> APROBADA;
            case "PENDING" -> PENDIENTE;
            case "NOT_AUTHORIZED" -> RECHAZADA;
            case "FAILED" -> FALLIDA;
            default -> null;
        };
    }
}
