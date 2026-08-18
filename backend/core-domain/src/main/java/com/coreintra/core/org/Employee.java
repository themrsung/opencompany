package com.coreintra.core.org;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A person employed by one of the companies.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No salary, no bank account, no payslip, no compensation history. This
 * installation holds employment facts only. That is a scoping decision, and it
 * is what keeps the temporary-master threat model tractable: a support session
 * that somehow read every employee row still learns nobody's pay.
 *
 * <p>If compensation is ever added it needs its own table, its own
 * {@code hr.compensation:*} capability family, and field-level encryption — not
 * three more columns here.
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    /** The client's own employee number. Not the primary key; clients reuse and renumber. */
    @Column(name = "employee_number", length = 40)
    private String employeeNumber;

    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    /** Latin transliteration or legal English name, for overseas subsidiaries. */
    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "hired_on")
    private LocalDate hiredOn;

    /**
     * Last day of employment. Drives the offboarding checklist and account
     * deactivation; a 사직서 approval writes it.
     */
    @Column(name = "terminated_on")
    private LocalDate terminatedOn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Employee() {
    }

    public Employee(String id, String companyId, String nameKo) {
        this.id = id;
        this.companyId = companyId;
        this.nameKo = nameKo;
        this.createdAt = OffsetDateTime.now();
    }

    /** Employed on {@code date}: hired on or before it, and not yet terminated. */
    public boolean isEmployedOn(LocalDate date) {
        if (hiredOn != null && date.isBefore(hiredOn)) {
            return false;
        }
        return terminatedOn == null || !date.isAfter(terminatedOn);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String employeeNumber() {
        return employeeNumber;
    }

    public void setEmployeeNumber(String value) {
        this.employeeNumber = value;
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

    public String email() {
        return email;
    }

    public void setEmail(String value) {
        this.email = value;
    }

    public LocalDate hiredOn() {
        return hiredOn;
    }

    public void setHiredOn(LocalDate value) {
        this.hiredOn = value;
    }

    public LocalDate terminatedOn() {
        return terminatedOn;
    }

    public void terminate(LocalDate lastDay) {
        this.terminatedOn = lastDay;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
