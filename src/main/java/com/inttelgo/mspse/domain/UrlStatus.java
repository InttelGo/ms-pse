package com.inttelgo.mspse.domain;

/** Ids sembrados de {@link EstadoUrl}. */
public enum UrlStatus {
    /** Se puede pagar mientras no venza. */
    ACTIVA(1L),
    /** Ya se pagó; no se puede modificar ni eliminar. */
    PAGADA(2L),
    /** Deshabilitada a mano; se puede volver a activar. */
    DESHABILITADA(3L);

    private final long id;

    UrlStatus(long id) {
        this.id = id;
    }

    public long id() {
        return id;
    }

    public boolean is(EstadoUrl status) {
        return status != null && status.getId() != null && status.getId() == id;
    }

    public static boolean isSystem(Long id) {
        for (UrlStatus status : values()) {
            if (id != null && status.id == id) {
                return true;
            }
        }
        return false;
    }
}
