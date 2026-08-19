package com.coreintra.app.api.attendance;

import com.coreintra.app.api.approval.ContentVersions;
import com.coreintra.app.api.http.ETags;
import com.coreintra.attendance.domain.StatusBehaviour;
import com.coreintra.attendance.entity.AttendanceStatusType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A 근태 상태 — 근무, 재택, 외근, 자리비움, 휴가, 퇴근, or anything the client
 * invented.
 *
 * <h2>Custom statuses are not a lesser kind</h2>
 *
 * <p>§5 makes them first-class, and the only thing that distinguishes a built-in
 * from a client-defined one is {@link #isBuiltIn()}, which exists so the UI can
 * warn before retiring something the seeded data depends on. Everything that
 * <em>behaves</em> differently does so because of the behaviour flags, never
 * because of the code.
 *
 * <p>Nothing branches on the labels. A client that renames 외근 to "현장" changes
 * what people read and nothing else, which is the point of having a code.
 */
@Schema(name = "AttendanceStatusType", description = "One 근태 상태 and what it means.")
public class AttendanceStatusView {

    /** The flags that decide what a status does, as opposed to what it is called. */
    @Schema(name = "AttendanceStatusBehaviour")
    public static class BehaviourView {
        private final boolean countsAsWorking;
        private final boolean requiresApproval;
        private final boolean deductsLeaveBalance;
        private final boolean visibleToPeers;
        private final Long autoExpiresAfterSeconds;

        BehaviourView(StatusBehaviour behaviour) {
            this.countsAsWorking = behaviour.countsAsWorking();
            this.requiresApproval = behaviour.requiresApproval();
            this.deductsLeaveBalance = behaviour.deductsLeaveBalance();
            this.visibleToPeers = behaviour.visibleToPeers();
            this.autoExpiresAfterSeconds = behaviour.autoExpiresAfter() == null
                    ? null : Long.valueOf(behaviour.autoExpiresAfter().getSeconds());
        }

        @Schema(description = "Whether time in this status counts towards working hours.")
        public boolean isCountsAsWorking() {
            return countsAsWorking;
        }

        @Schema(description = "Whether entering it needs a 결재 document first.")
        public boolean isRequiresApproval() {
            return requiresApproval;
        }

        @Schema(description = "Whether it spends leave days. Always accompanied by "
                + "requiresApproval: a status that spent someone's balance with nobody having "
                + "agreed to it is refused at definition time.")
        public boolean isDeductsLeaveBalance() {
            return deductsLeaveBalance;
        }

        @Schema(description = "Whether colleagues see it on the who's-in board. A status "
                + "that is not visible to peers is omitted from other people's view of the "
                + "board rather than shown as 'unknown'.")
        public boolean isVisibleToPeers() {
            return visibleToPeers;
        }

        @Schema(description = "How long before an open record in this status is swept "
                + "closed, in seconds. Null means it stays open until somebody ends it — "
                + "which is right for 근무 and wrong for 자리비움.")
        public Long getAutoExpiresAfterSeconds() {
            return autoExpiresAfterSeconds;
        }
    }

    private final String id;
    private final String companyId;
    private final String code;
    private final String labelKo;
    private final String labelEn;
    private final String colour;
    private final String icon;
    private final boolean builtIn;
    private final boolean active;
    private final int sortOrder;
    private final BehaviourView behaviour;
    private final String etag;

    private AttendanceStatusView(AttendanceStatusType status) {
        this.id = status.id();
        this.companyId = status.companyId();
        this.code = status.code();
        this.labelKo = status.labelKo();
        this.labelEn = status.labelEn();
        this.colour = status.colour();
        this.icon = status.icon();
        this.builtIn = status.isBuiltIn();
        this.active = status.isActive();
        this.sortOrder = status.sortOrder();
        this.behaviour = new BehaviourView(status.behaviour());
        this.etag = tagOf(status);
    }

    public static AttendanceStatusView from(AttendanceStatusType status) {
        return new AttendanceStatusView(status);
    }

    /** Derived from the mutable fields, because the row carries no version. */
    public static String tagOf(AttendanceStatusType status) {
        StatusBehaviour behaviour = status.behaviour();
        return ETags.of(status.id(), ContentVersions.of(
                status.labelKo(), status.labelEn(), status.colour(), status.icon(),
                Integer.valueOf(status.sortOrder()), Boolean.valueOf(status.isActive()),
                Boolean.valueOf(behaviour.countsAsWorking()),
                Boolean.valueOf(behaviour.requiresApproval()),
                Boolean.valueOf(behaviour.deductsLeaveBalance()),
                Boolean.valueOf(behaviour.visibleToPeers()),
                behaviour.autoExpiresAfter()));
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    @Schema(description = "Stable. Records refer to statuses by this, so it never changes.",
            example = "REMOTE")
    public String getCode() {
        return code;
    }

    @Schema(description = "The authoritative label: Korean is the default locale and a "
            + "status with no Korean name is unusable on the board it appears on.",
            example = "재택")
    public String getLabelKo() {
        return labelKo;
    }

    @Schema(example = "Working from home")
    public String getLabelEn() {
        return labelEn;
    }

    public String getColour() {
        return colour;
    }

    public String getIcon() {
        return icon;
    }

    @Schema(description = "Shipped with the installation rather than defined by the client.")
    public boolean isBuiltIn() {
        return builtIn;
    }

    @Schema(description = "A retired status can no longer be chosen, but existing records "
            + "keep pointing at it — deleting the row would leave a year of timesheets "
            + "referring to nothing.")
    public boolean isActive() {
        return active;
    }

    @Schema(description = "The order the client arranged them in.")
    public int getSortOrder() {
        return sortOrder;
    }

    public BehaviourView getBehaviour() {
        return behaviour;
    }

    @Schema(description = "Send back as If-Match to change this status.")
    public String getEtag() {
        return etag;
    }
}
