package com.coreintra.documents.internal;

import com.coreintra.compat.Immutables;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * An export target produced outside the JVM, and what is true about it.
 *
 * <h2>Why these are not adapters</h2>
 *
 * <p>PDF and legacy {@code .doc} have no {@link DocumentAdapter}: nothing here
 * reads them, and nothing here writes them either. They are produced by the
 * conversion worker — headless LibreOffice, or mdv's own exporter for an mdv
 * source — so there is no capability declaration to derive a matrix row from.
 *
 * <p>They still appear in the export menu, which means a user still chooses
 * them, which means §6.5's rule applies: never let a user believe an export is
 * lossless when it is not. So the caveats are declared here, in one place, and
 * flow into both the published matrix and the export dialog.
 *
 * <h2>The awkward ones</h2>
 *
 * <p>Two of these are refusals rather than warnings, and both were found by
 * reading the pinned mdv build rather than its documentation. They are recorded
 * as data so the dialog can grey the option out with a reason instead of
 * offering something that will fail or, worse, succeed badly.
 */
public final class RenderTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String displayName;
    private final String producedBy;
    private final boolean offered;
    private final String cautionEn;
    private final String cautionKo;

    private RenderTarget(String id, String displayName, String producedBy, boolean offered,
            String cautionEn, String cautionKo) {
        this.id = id;
        this.displayName = displayName;
        this.producedBy = producedBy;
        this.offered = offered;
        this.cautionEn = cautionEn;
        this.cautionKo = cautionKo;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Which engine makes it. Recorded in the render metadata of every output. */
    public String producedBy() {
        return producedBy;
    }

    /** False when the option must be refused rather than warned about. */
    public boolean isOffered() {
        return offered;
    }

    public String cautionEn() {
        return cautionEn;
    }

    public String cautionKo() {
        return cautionKo;
    }

    /** Every target the export menu can show, in the order it should show them. */
    public static List<RenderTarget> all() {
        List<RenderTarget> targets = new ArrayList<RenderTarget>();

        targets.add(new RenderTarget("pdf", "PDF", "headless LibreOffice (pinned version)", true,
                "Rendered server-side, never by the browser. What it looks like depends on the "
                        + "fonts installed in the conversion container: a family the container "
                        + "does not have is substituted, and the substitution is named in the "
                        + "render metadata. If a regenerated PDF ever differs from the archived "
                        + "one, the archived one is authoritative.",
                "서버에서 생성됩니다. 변환 컨테이너에 설치된 글꼴에 따라 결과가 달라지며, 글꼴이 "
                        + "바뀐 경우에는 렌더링 정보에 대체된 글꼴 이름이 기록됩니다. 보관된 PDF와 "
                        + "다시 만든 PDF가 다를 경우 보관본이 우선합니다."));

        targets.add(new RenderTarget("pdf-from-mdv", "PDF (from an mdv document)",
                "mdv's own exporter, in the conversion worker", false,
                "REFUSED for any document containing Korean or other non-WinAnsi text. The "
                        + "pinned mdv build embeds no fonts at all: its PDF exporter draws with "
                        + "the standard 14 faces and renders every Korean codepoint as `?`, "
                        + "reporting MDV5100. A PDF of 물음표 that says 결재 on the tab is worse "
                        + "than no PDF, so the worker refuses it and the LibreOffice path is used "
                        + "instead.",
                "한글이 포함된 문서는 이 경로로 내보낼 수 없습니다. 현재 고정된 mdv 빌드는 글꼴을 "
                        + "포함하지 못하여 한글이 모두 물음표로 표시됩니다. 대신 LibreOffice 경로로 "
                        + "PDF를 만듭니다."));

        targets.add(new RenderTarget("pdf-a-3b", "PDF/A-3b (archival)",
                "mdv's own exporter, in the conversion worker", false,
                "REFUSED. The pinned mdv build accepts the profile and does nothing with it: no "
                        + "pdfaid XMP metadata, no OutputIntent, no embedded fonts — none of what "
                        + "PDF/A requires. Producing a file labelled archival that would fail "
                        + "validation is a promise this system would be making on the client's "
                        + "behalf to their auditor.",
                "현재 고정된 mdv 빌드에서는 PDF/A-3b를 만들 수 없습니다. 프로필을 지정해도 실제로는 "
                        + "적용되지 않으므로, 보존용이라고 표시된 파일을 내주지 않습니다."));

        targets.add(new RenderTarget("pdf-ua-1", "PDF/UA-1 (accessible)",
                "mdv's own exporter, in the conversion worker", false,
                "REFUSED. The pinned build stamps the ISO 14289-1 conformance claim into a file "
                        + "whose fonts are not embedded, which that standard requires. It checks "
                        + "only that figures carry an /Alt. A false accessibility claim is worse "
                        + "than an absent one.",
                "현재 고정된 mdv 빌드에서는 PDF/UA-1을 보장할 수 없습니다. 접근성 준수 표시만 "
                        + "기록될 뿐 실제 요건을 충족하지 못하므로 거부합니다."));

        targets.add(new RenderTarget("doc", "DOC (legacy Word 97)",
                "headless LibreOffice (pinned version)", true,
                "Legacy and lossy, and labelled as such in the interface. Content controls do not "
                        + "exist in this format: the field values are written as ordinary text and "
                        + "the bindings are gone. Offered because counterparties still send and "
                        + "expect it.",
                "예전 형식이며 변환 과정에서 서식이 일부 손실됩니다. 이 형식에는 입력 항목(필드)이 "
                        + "없으므로 값은 일반 텍스트로 저장되고 필드 연결은 사라집니다."));

        return Immutables.copyOf(targets);
    }
}
