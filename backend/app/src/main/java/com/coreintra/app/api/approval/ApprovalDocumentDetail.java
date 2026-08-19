package com.coreintra.app.api.approval;

import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.domain.ApprovalStep;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.ApprovalActionEntity;
import com.coreintra.approval.service.ApprovalDocumentView;
import com.coreintra.compat.Immutables;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * A document, its resolved 결재선 and its whole trail, in one response.
 *
 * <p>Four queries produce it and one round trip delivers it, because a 결재
 * screen always renders all four parts at once. Splitting them across endpoints
 * would let the client paint a document beside a line loaded a moment later, and
 * the moment in between is exactly when somebody signs.
 */
@Schema(name = "ApprovalDocumentDetail",
        description = "An approval document with its resolved line and its immutable trail.")
public class ApprovalDocumentDetail {

    /** How representative authority is exercised for this document's company. */
    @Schema(name = "RepresentationMode")
    public static class RepresentationView {
        private final String kind;
        private final int requiredApprovals;
        private final int designatedRepresentatives;
        private final String label;

        RepresentationView(RepresentationMode mode) {
            this.kind = mode.kind().name();
            this.requiredApprovals = mode.requiredApprovals();
            this.designatedRepresentatives = mode.designatedRepresentatives();
            this.label = mode.toString();
        }

        @Schema(description = "SEVERAL (각자대표) or JOINT (공동대표).", example = "JOINT")
        public String getKind() {
            return kind;
        }

        @Schema(description = "The quorum. Always at least two under JOINT — a joint mode "
                + "satisfiable by one representative is 각자대표 under another name.")
        public int getRequiredApprovals() {
            return requiredApprovals;
        }

        public int getDesignatedRepresentatives() {
            return designatedRepresentatives;
        }

        @Schema(example = "공동대표 (2 of 3)")
        public String getLabel() {
            return label;
        }
    }

    private final ApprovalDocumentSummary document;
    private final RepresentationView representation;
    private final String lineSummary;
    private final List<ApprovalStepView> steps;
    private final List<ApprovalActionView> trail;
    private final boolean awaitingMe;

    private ApprovalDocumentDetail(ApprovalDocumentSummary document,
            RepresentationView representation, String lineSummary, List<ApprovalStepView> steps,
            List<ApprovalActionView> trail, boolean awaitingMe) {
        this.document = document;
        this.representation = representation;
        this.lineSummary = lineSummary;
        this.steps = Immutables.copyOf(steps);
        this.trail = Immutables.copyOf(trail);
        this.awaitingMe = awaitingMe;
    }

    /**
     * @param callerAccountId whose "is this waiting on me?" question to answer.
     *        Asked of the rebuilt state machine rather than re-derived here, so
     *        the API and the domain cannot come to different conclusions.
     */
    public static ApprovalDocumentDetail from(ApprovalDocumentView view, String callerAccountId) {
        ApprovalLine line = view.line();
        List<ApprovalStepView> steps = new ArrayList<ApprovalStepView>();
        if (line != null) {
            for (ApprovalStep step : line.steps()) {
                steps.add(ApprovalStepView.from(step, view.approversOf(step.id())));
            }
        }
        List<ApprovalActionView> trail = new ArrayList<ApprovalActionView>();
        for (ApprovalActionEntity action : view.trail()) {
            trail.add(ApprovalActionView.from(action));
        }
        return new ApprovalDocumentDetail(
                ApprovalDocumentSummary.from(view.document()),
                line == null ? null : new RepresentationView(line.representationMode()),
                line == null ? null : line.summary(),
                steps,
                trail,
                view.isAwaiting(callerAccountId));
    }

    public ApprovalDocumentSummary getDocument() {
        return document;
    }

    @Schema(description = "Null while the document is a draft with no line yet.")
    public RepresentationView getRepresentation() {
        return representation;
    }

    @Schema(description = "One line of prose about where the document has got to.")
    public String getLineSummary() {
        return lineSummary;
    }

    @Schema(description = "In position order. Empty for a draft, which has no line until "
            + "it is submitted.")
    public List<ApprovalStepView> getSteps() {
        return steps;
    }

    @Schema(description = "Append-only, in business-time order: date first, then offset.")
    public List<ApprovalActionView> getTrail() {
        return trail;
    }

    @Schema(description = "True when the calling account is expected to act right now.")
    public boolean isAwaitingMe() {
        return awaitingMe;
    }
}
