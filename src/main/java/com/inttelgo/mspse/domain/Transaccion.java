package com.inttelgo.mspse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transacción iniciada en PSE. {@code buildId} es la factura creada en ms-build cuando se aprueba
 * y alguna línea tiene servicio; {@code collectionBuildId} queda para compatibilidad (los cobros van por línea).
 */
@Entity
@Table(name = "Transaccion", indexes = {
        @Index(name = "ux_transaccion_ticket", columnList = "number_ticket", unique = true),
        @Index(name = "ix_transaccion_status", columnList = "status_id")
})
public class Transaccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number_ticket", nullable = false, length = 50)
    private String numberTicket;

    /** CUS que devuelve PSE. */
    @Column(name = "code_trazability", length = 100)
    private String codeTrazability;

    @Column(name = "transaction_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal transactionValue;

    /** Porcentaje de descuento (1 a 100) sobre las líneas con servicio; nulo sin descuento. */
    @Column(name = "discount")
    private Integer discount;

    @Column(name = "iva_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal ivaValue;

    @Column(name = "bank_code", length = 20)
    private String bankCode;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "type_document", length = 10)
    private String typeDocument;

    @Column(name = "identification", length = 30)
    private String identification;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "create_at", nullable = false)
    private Instant createAt;

    /** Usuario "Mi Recaudo PSE" ({@code PSE_SYSTEM_USER_ID}). */
    @Column(name = "create_by", nullable = false)
    private Long createBy;

    @Column(name = "response_at")
    private Instant responseAt;

    @Column(name = "update_at")
    private Instant updateAt;

    @Column(name = "first_reference", length = 100)
    private String firstReference;

    @Column(name = "second_reference", length = 100)
    private String secondReference;

    @Column(name = "thirth_reference", length = 100)
    private String thirthReference;

    @Column(name = "entitycode", length = 50)
    private String entitycode;

    /** Banco que reporta PSE al consultar la transacción. */
    @Column(name = "financial_institutionCode", length = 20)
    private String financialInstitutionCode;

    @Column(name = "service_code", length = 50)
    private String serviceCode;

    @Column(name = "bank_process_date")
    private Instant bankProcessDate;

    @Column(name = "trasnsaction_cycle", length = 20)
    private String trasnsactionCycle;

    @Column(name = "payment_description", length = 255)
    private String paymentDescription;

    @Column(name = "address", length = 255)
    private String address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "urlGenerated")
    private GenerateUrl urlGenerated;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private EstadoTransaccion status;

    @Column(name = "build_id")
    private Long buildId;

    @Column(name = "collection_build_id")
    private Long collectionBuildId;

    public Long getId() {
        return id;
    }

    public String getNumberTicket() {
        return numberTicket;
    }

    public void setNumberTicket(String numberTicket) {
        this.numberTicket = numberTicket;
    }

    public String getCodeTrazability() {
        return codeTrazability;
    }

    public void setCodeTrazability(String codeTrazability) {
        this.codeTrazability = codeTrazability;
    }

    public BigDecimal getTransactionValue() {
        return transactionValue;
    }

    public void setTransactionValue(BigDecimal transactionValue) {
        this.transactionValue = transactionValue;
    }

    public Integer getDiscount() {
        return discount;
    }

    public void setDiscount(Integer discount) {
        this.discount = discount;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public BigDecimal getIvaValue() {
        return ivaValue;
    }

    public void setIvaValue(BigDecimal ivaValue) {
        this.ivaValue = ivaValue;
    }

    public String getBankCode() {
        return bankCode;
    }

    public void setBankCode(String bankCode) {
        this.bankCode = bankCode;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getTypeDocument() {
        return typeDocument;
    }

    public void setTypeDocument(String typeDocument) {
        this.typeDocument = typeDocument;
    }

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Instant getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Instant createAt) {
        this.createAt = createAt;
    }

    public Instant getResponseAt() {
        return responseAt;
    }

    public void setResponseAt(Instant responseAt) {
        this.responseAt = responseAt;
    }

    public Instant getUpdateAt() {
        return updateAt;
    }

    public void setUpdateAt(Instant updateAt) {
        this.updateAt = updateAt;
    }

    public String getFirstReference() {
        return firstReference;
    }

    public void setFirstReference(String firstReference) {
        this.firstReference = firstReference;
    }

    public String getSecondReference() {
        return secondReference;
    }

    public void setSecondReference(String secondReference) {
        this.secondReference = secondReference;
    }

    public String getThirthReference() {
        return thirthReference;
    }

    public void setThirthReference(String thirthReference) {
        this.thirthReference = thirthReference;
    }

    public String getEntitycode() {
        return entitycode;
    }

    public void setEntitycode(String entitycode) {
        this.entitycode = entitycode;
    }

    public String getFinancialInstitutionCode() {
        return financialInstitutionCode;
    }

    public void setFinancialInstitutionCode(String financialInstitutionCode) {
        this.financialInstitutionCode = financialInstitutionCode;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public Instant getBankProcessDate() {
        return bankProcessDate;
    }

    public void setBankProcessDate(Instant bankProcessDate) {
        this.bankProcessDate = bankProcessDate;
    }

    public String getTrasnsactionCycle() {
        return trasnsactionCycle;
    }

    public void setTrasnsactionCycle(String trasnsactionCycle) {
        this.trasnsactionCycle = trasnsactionCycle;
    }

    public String getPaymentDescription() {
        return paymentDescription;
    }

    public void setPaymentDescription(String paymentDescription) {
        this.paymentDescription = paymentDescription;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public GenerateUrl getUrlGenerated() {
        return urlGenerated;
    }

    public void setUrlGenerated(GenerateUrl urlGenerated) {
        this.urlGenerated = urlGenerated;
    }

    public EstadoTransaccion getStatus() {
        return status;
    }

    public void setStatus(EstadoTransaccion status) {
        this.status = status;
    }

    public Long getBuildId() {
        return buildId;
    }

    public void setBuildId(Long buildId) {
        this.buildId = buildId;
    }

    public Long getCollectionBuildId() {
        return collectionBuildId;
    }

    public void setCollectionBuildId(Long collectionBuildId) {
        this.collectionBuildId = collectionBuildId;
    }
}
