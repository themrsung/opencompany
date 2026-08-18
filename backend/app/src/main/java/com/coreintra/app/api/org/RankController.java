package com.coreintra.app.api.org;

import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.RankService;
import com.coreintra.core.service.RecordNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직급 — the seniority ladder, per company.
 *
 * <p>Nested under the company because the ladder <em>is</em> company data: 부장
 * at 본사 and 부장 at a 자회사 are different rows with different grants, and an
 * installation that shares one ladder across entities has quietly decided that
 * a subsidiary cannot have its own titles.
 *
 * <p>Reordering is one operation over the whole ladder rather than a seniority
 * field on each rung. Setting them one at a time passes through states where two
 * rungs share a seniority, and "the 상급자 of this unit" — which the approval
 * module resolves on every document — has no answer in those states.
 */
@RestController
@RequestMapping("/api/v1/org/companies/{companyId}/ranks")
@Tag(name = "Org — ranks",
        description = "The client-defined 직급 ladder. Nothing about Korean titles is hardcoded: "
                + "labels and ordering are both data.")
public class RankController {

    private final RankService ranks;
    private final CurrentPrincipal currentPrincipal;

    public RankController(RankService ranks, CurrentPrincipal currentPrincipal) {
        this.ranks = ranks;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The ladder, most senior first")
    public CursorPage<RankView> list(
            @PathVariable String companyId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return Pages.page(ranks.list(caller, companyId, on), RankView.KEYS, RankView.MAPPER,
                cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Add a rung")
    public ResponseEntity<RankView> create(
            @PathVariable String companyId,
            @Valid @RequestBody CreateRankRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        return tagged(ranks.create(caller, companyId, body.getCode(), body.getLabelKo(),
                body.getLabelEn(), body.getSeniority(), body.isRepresentative(), on));
    }

    @PatchMapping("/{rankId}/labels")
    @Operation(summary = "Relabel a rung", description = "Requires If-Match.")
    public ResponseEntity<RankView> relabel(
            @PathVariable String companyId,
            @PathVariable String rankId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody LabelsRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, rankId, ifMatch, on);
        return tagged(ranks.relabel(caller, rankId, body.getLabelKo(), body.getLabelEn(), on));
    }

    @PatchMapping("/{rankId}/representative")
    @Operation(summary = "Mark or unmark 대표이사",
            description = "Drives the representation modes the approval module applies. "
                    + "Requires If-Match.")
    public ResponseEntity<RankView> setRepresentative(
            @PathVariable String companyId,
            @PathVariable String rankId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody RepresentativeRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, rankId, ifMatch, on);
        return tagged(ranks.setRepresentative(caller, rankId, body.isRepresentative(), on));
    }

    @PutMapping("/order")
    @Operation(summary = "Reorder the whole ladder",
            description = "Every live rung, most senior first. Requires hr.rank:reorder, which "
                    + "is not hr.rank:update — a reorder moves everyone at once.")
    public CursorPage<RankView> reorder(
            @PathVariable String companyId,
            @Valid @RequestBody ReorderRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        // No If-Match: the precondition would have to name every rung's tag, and
        // a partial match would be meaningless because the operation is
        // all-or-nothing. The service validates the id set against the live
        // ladder instead and refuses an incomplete or unfamiliar one, which
        // catches the same stale-editor case with a message that says so.
        List<Rank> ladder = ranks.reorder(caller, companyId, body.getRankIdsMostSeniorFirst(), on);
        return Pages.page(ladder, RankView.KEYS, RankView.MAPPER, null, Integer.valueOf(ladder.size()));
    }

    @DeleteMapping("/{rankId}")
    @Operation(summary = "Retire a rung",
            description = "Refused while anyone holds it. Requires If-Match.")
    public ResponseEntity<RankView> retire(
            @PathVariable String companyId,
            @PathVariable String rankId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        requireCurrent(caller, companyId, rankId, ifMatch, on);
        return tagged(ranks.retire(caller, rankId, on));
    }

    /**
     * Finds the rung inside its company's ladder and enforces the precondition.
     *
     * <p>{@code RankService} has no read-by-id, so the current state comes from
     * the company's list — which is also why these paths are nested under the
     * company rather than sitting at {@code /org/ranks/{id}}. A flat path would
     * have needed a repository lookup here to find out which company to ask
     * about, and a controller holding a repository is the layering violation
     * ArchUnit fails the build on.
     */
    private void requireCurrent(PermissionPrincipal caller, String companyId, String rankId,
            String ifMatch, LocalDate on) {
        Rank current = require(caller, companyId, rankId, on);
        ETags.require(ifMatch, RankView.tagOf(current), "rank " + current.labelKo());
    }

    private Rank require(PermissionPrincipal caller, String companyId, String rankId, LocalDate on) {
        for (Rank rank : ranks.list(caller, companyId, on)) {
            if (rank.id().equals(rankId)) {
                return rank;
            }
        }
        throw new RecordNotFoundException("no such rank in this company: " + rankId);
    }

    private static ResponseEntity<RankView> tagged(Rank rank) {
        return ResponseEntity.ok().eTag(RankView.tagOf(rank)).body(RankView.from(rank));
    }

    /** A new rung. */
    public static class CreateRankRequest {
        @NotBlank
        @Size(max = 40)
        private String code;
        @NotBlank
        @Size(max = 100)
        private String labelKo;
        @Size(max = 100)
        private String labelEn;
        private int seniority;
        private boolean representative;

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }

        public String getLabelKo() {
            return labelKo;
        }

        public void setLabelKo(String value) {
            this.labelKo = value;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public void setLabelEn(String value) {
            this.labelEn = value;
        }

        /** Higher is more senior. The gaps are the client's to choose. */
        public int getSeniority() {
            return seniority;
        }

        public void setSeniority(int value) {
            this.seniority = value;
        }

        public boolean isRepresentative() {
            return representative;
        }

        public void setRepresentative(boolean value) {
            this.representative = value;
        }
    }

    /** Both labels, for the same reason a rename carries both names. */
    public static class LabelsRequest {
        @NotBlank
        @Size(max = 100)
        private String labelKo;
        @Size(max = 100)
        private String labelEn;

        public String getLabelKo() {
            return labelKo;
        }

        public void setLabelKo(String value) {
            this.labelKo = value;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public void setLabelEn(String value) {
            this.labelEn = value;
        }
    }

    /** 대표 or not. */
    public static class RepresentativeRequest {
        private boolean representative;

        public boolean isRepresentative() {
            return representative;
        }

        public void setRepresentative(boolean value) {
            this.representative = value;
        }
    }

    /** The whole ladder, most senior first. Partial lists are refused by the service. */
    public static class ReorderRequest {
        @NotEmpty
        private List<String> rankIdsMostSeniorFirst;

        public List<String> getRankIdsMostSeniorFirst() {
            return rankIdsMostSeniorFirst == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(rankIdsMostSeniorFirst);
        }

        public void setRankIdsMostSeniorFirst(List<String> value) {
            this.rankIdsMostSeniorFirst = value;
        }
    }
}
