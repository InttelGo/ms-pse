package com.inttelgo.mspse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Enlace de pago con valor fijo que se paga por PSE desde el landing. */
@Entity
@Table(name = "GenerateUrl")
public class GenerateUrl extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500)
    private String url;

    @Column(nullable = false, length = 255)
    private String concept;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    /** Último día en que se puede pagar (incluido). */
    @Column(name = "expiry_at", nullable = false)
    private LocalDate expiryAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private EstadoUrl status;

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getConcept() {
        return concept;
    }

    public void setConcept(String concept) {
        this.concept = concept;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public LocalDate getExpiryAt() {
        return expiryAt;
    }

    public void setExpiryAt(LocalDate expiryAt) {
        this.expiryAt = expiryAt;
    }

    public EstadoUrl getStatus() {
        return status;
    }

    public void setStatus(EstadoUrl status) {
        this.status = status;
    }
}
