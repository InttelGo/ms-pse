package com.inttelgo.mspse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Estado de una transacción (las URLs de pago tienen el suyo: {@link EstadoUrl}). Los ids se siembran en orden y el código los usa:
 * ver {@link TransactionStatus}.
 */
@Entity
@Table(name = "EstadoTransaccion")
public class EstadoTransaccion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String status;

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
