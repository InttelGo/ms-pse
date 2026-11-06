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

/**
 * Línea de una transacción. Con {@code serviceId} entra a la factura de ms-build (con {@code extraId} es
 * un extra de ese servicio, como una pantalla WIN+, en su propia línea); con
 * {@code collectionBuildId} se registra como pago de una cuota del cobro y {@code paymentId} guarda
 * el pago creado. Las dos se llenan cuando la transacción se aprueba.
 */
@Entity
@Table(name = "DetalleTransaccion")
public class DetalleTransaccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String description;

    @Column(name = "internet_value", precision = 14, scale = 2)
    private BigDecimal internetValue;

    @Column(name = "telephony_value", precision = 14, scale = 2)
    private BigDecimal telephonyValue;

    @Column(name = "television_value", precision = 14, scale = 2)
    private BigDecimal televisionValue;

    /** Lo que se cobra en la línea, con el descuento ya restado. Sin descuento = valor de la línea. */
    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    /** Pesos que descontó el % de la transacción en esta línea ({@code value + discount} = valor sin descuento). */
    @Column(name = "discount", precision = 14, scale = 2)
    private BigDecimal discount;

    @Column(name = "service_id")
    private Long serviceId;

    /** Extra del servicio (p. ej. pantalla WIN+) en ms-service; la línea lleva también su {@code serviceId}. */
    @Column(name = "extra_id")
    private Long extraId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "collection_build_id")
    private Long collectionBuildId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaccion transaction;

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getInternetValue() {
        return internetValue;
    }

    public void setInternetValue(BigDecimal internetValue) {
        this.internetValue = internetValue;
    }

    public BigDecimal getTelephonyValue() {
        return telephonyValue;
    }

    public void setTelephonyValue(BigDecimal telephonyValue) {
        this.telephonyValue = telephonyValue;
    }

    public BigDecimal getTelevisionValue() {
        return televisionValue;
    }

    public void setTelevisionValue(BigDecimal televisionValue) {
        this.televisionValue = televisionValue;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public Long getExtraId() {
        return extraId;
    }

    public void setExtraId(Long extraId) {
        this.extraId = extraId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Long getCollectionBuildId() {
        return collectionBuildId;
    }

    public void setCollectionBuildId(Long collectionBuildId) {
        this.collectionBuildId = collectionBuildId;
    }

    public Transaccion getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaccion transaction) {
        this.transaction = transaction;
    }
}
