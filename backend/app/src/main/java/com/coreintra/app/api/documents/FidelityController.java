package com.coreintra.app.api.documents;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.documents.internal.FidelityMatrix;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fidelity matrix, as data.
 *
 * <p>§6.5 requires the relevant row be surfaced in the interface at export time,
 * and §13 tests for it. {@code docs/documents/fidelity.md} is generated from the
 * same declarations, so a developer reading the page and a user reading the
 * export dialog cannot be told different things — but a Markdown page cannot be
 * laid out as a row, so the dialog reads this.
 *
 * <p>Every export response already carries its own row. This endpoint exists for
 * the settings page that shows the whole table, and so that a client can warn
 * before the user has committed to an export.
 *
 * <h2>Why there is no permission on it</h2>
 *
 * <p>The matrix describes the software, not any company's data: it is the same
 * for every tenant and reveals nothing about any of them. It still requires an
 * authenticated caller, because an unauthenticated endpoint is a decision that
 * should be made deliberately and this one has no reason to be public.
 */
@RestController
@RequestMapping("/api/v1/documents/fidelity")
@Tag(name = "Documents — fidelity",
        description = "What survives, what degrades and what is dropped, per format pair. "
                + "Generated from the adapters' own declarations, never hand-maintained.")
public class FidelityController {

    private final CurrentPrincipal current;

    public FidelityController(CurrentPrincipal current) {
        this.current = current;
    }

    @GetMapping
    @Operation(summary = "The fidelity matrix",
            description = "Every format pair, or one pair when from and to are given. Requires "
                    + "only an authenticated caller: this describes the software, not any "
                    + "company's documents.")
    public ResponseEntity<Object> matrix(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {

        current.require();

        if (!Texts.isBlank(from) || !Texts.isBlank(to)) {
            if (Texts.isBlank(from) || Texts.isBlank(to)) {
                return DocumentProblems.badRequest("pair_needs_both",
                        "A fidelity row is a pair",
                        "Give both from and to, or neither. A single format's capabilities are "
                                + "not the question a user has at export time; the pair is.");
            }
            return ResponseEntity.ok((Object) FidelityMatrixSource.row(
                    Texts.strip(from).toLowerCase(Locale.ROOT),
                    Texts.strip(to).toLowerCase(Locale.ROOT),
                    Texts.strip(to).toUpperCase(Locale.ROOT)));
        }

        List<FidelityRowView> rows = new ArrayList<FidelityRowView>();
        for (FidelityMatrix.Pair pair : FidelityMatrixSource.matrix().pairs()) {
            rows.add(FidelityRowView.of(pair));
        }
        return ResponseEntity.ok((Object) Immutables.copyOf(rows));
    }
}
