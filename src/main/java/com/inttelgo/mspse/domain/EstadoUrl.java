package com.inttelgo.mspse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Estado de una URL de pago, separado del de la transacción. Los ids se siembran en orden y el código los
 * usa: ver {@link UrlStatus}. El vencimiento no es un estado: se calcula con {@code expiry_at}.
 */
@Entity
@Table(name = "EstadoUrl")
public class EstadoUrl extends Auditable {

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
