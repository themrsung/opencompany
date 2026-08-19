package com.coreintra.attendance.entity;

import com.coreintra.attendance.domain.StatusBehaviour;
import java.time.Duration;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A status a person can be in — built-in or client-defined, identically treated.
 *
 * <p>{@code builtIn} marks the six seeded rows so they can be restored to
 * factory state. It grants them no other privilege: the behaviour flags are the
 * only thing the system reads, and a client status carrying the same flags
 * behaves the same way in every report and every view.
 */
@Entity
@Table(name = "attendance_status_type")
public class AttendanceStatusType {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "label_ko", nullable = false, length = 100)
    private String labelKo;

    @Column(name = "label_en", length = 100)
    private String labelEn;

    @Column(name = "colour", length = 16)
    private String colour;

    @Column(name = "icon", length = 40)
    private String icon;

    @Column(name = "counts_as_working", nullable = false)
    private boolean countsAsWorking;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "deducts_leave_balance", nullable = false)
    private boolean deductsLeaveBalance;

    @Column(name = "visible_to_peers", nullable = false)
    private boolean visibleToPeers = true;

    @Column(name = "auto_expires_after_seconds")
    private Integer autoExpiresAfterSeconds;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected AttendanceStatusType() {
    }

    public AttendanceStatusType(String id, String companyId, String code, String labelKo,
            StatusBehaviour behaviour) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.labelKo = labelKo;
        applyBehaviour(behaviour);
    }

    public final void applyBehaviour(StatusBehaviour behaviour) {
        this.countsAsWorking = behaviour.countsAsWorking();
        this.requiresApproval = behaviour.requiresApproval();
        this.deductsLeaveBalance = behaviour.deductsLeaveBalance();
        this.visibleToPeers = behaviour.visibleToPeers();
        this.autoExpiresAfterSeconds = behaviour.autoExpiresAfter() == null
                ? null
                : Integer.valueOf((int) behaviour.autoExpiresAfter().getSeconds());
    }

    public StatusBehaviour behaviour() {
        StatusBehaviour.Builder builder = StatusBehaviour.builder()
                .countsAsWorking(countsAsWorking)
                .requiresApproval(requiresApproval)
                .deductsLeaveBalance(deductsLeaveBalance)
                .visibleToPeers(visibleToPeers);
        if (autoExpiresAfterSeconds != null) {
            builder.autoExpiresAfter(Duration.ofSeconds(autoExpiresAfterSeconds.longValue()));
        }
        return builder.build();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String labelKo() {
        return labelKo;
    }

    public String labelEn() {
        return labelEn;
    }

    public void relabel(String ko, String en) {
        this.labelKo = ko;
        this.labelEn = en;
    }

    public String colour() {
        return colour;
    }

    public void setColour(String value) {
        this.colour = value;
    }

    public String icon() {
        return icon;
    }

    public void setIcon(String value) {
        this.icon = value;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void markBuiltIn() {
        this.builtIn = true;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int value) {
        this.sortOrder = value;
    }
}
