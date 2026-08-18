package com.coreintra.core.org;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A legal entity. Multi-entity from day one: 본사, 지사 and 자회사 are all
 * companies, distinguished by {@link CompanyKind} and by who owns them.
 *
 * <p>Nothing about the Korean corporate structure is hardcoded — the kind is a
 * label on the row, and permission scope never assumes that a parent company's
 * staff reach a subsidiary's data. Crossing that boundary needs an explicit
 * {@link com.coreintra.core.permission.PermissionScope#ALL} grant.
 */
@Entity
@Table(name = "company")
public class Company {

    /** What sort of entity this is. Presentation and reporting only. */
    public enum CompanyKind {
        /** 본사 — the head office. */
        HEAD_OFFICE,
        /** 지사 — a branch of the same legal entity. */
        BRANCH,
        /** 자회사 — a separate legal entity under common ownership. */
        SUBSIDIARY
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CompanyKind kind;

    /** The owning company for a 지사/자회사, or null for a 본사. */
    @Column(name = "parent_company_id", length = 36)
    private String parentCompanyId;

    @Column(name = "business_registration_number", length = 40)
    private String businessRegistrationNumber;

    /** The book's base currency code, e.g. KRW. Used only when accounting is on. */
    @Column(name = "base_currency_code", length = 12)
    private String baseCurrencyCode;

    @Column(name = "established_on")
    private LocalDate establishedOn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** UTC, what the machine observed. Never conflated with business time. */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Company() {
    }

    public Company(String id, String code, String nameKo, CompanyKind kind) {
        this.id = id;
        this.code = code;
        this.nameKo = nameKo;
        this.kind = kind;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public void rename(String nameKo, String nameEn) {
        this.nameKo = nameKo;
        this.nameEn = nameEn;
    }

    public CompanyKind kind() {
        return kind;
    }

    public String parentCompanyId() {
        return parentCompanyId;
    }

    public void setParentCompanyId(String value) {
        this.parentCompanyId = value;
    }

    public String baseCurrencyCode() {
        return baseCurrencyCode;
    }

    public void setBaseCurrencyCode(String value) {
        this.baseCurrencyCode = value;
    }

    public String businessRegistrationNumber() {
        return businessRegistrationNumber;
    }

    public void setBusinessRegistrationNumber(String value) {
        this.businessRegistrationNumber = value;
    }

    public LocalDate establishedOn() {
        return establishedOn;
    }

    public void setEstablishedOn(LocalDate value) {
        this.establishedOn = value;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
