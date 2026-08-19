package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.documents.ooxml.OoxmlPackage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Evidence for ADR 0007's architecture, not just an assertion of it.
 *
 * <p>The document core deliberately keeps parts as raw bytes and writes them
 * back verbatim, rather than parsing into an object model and re-serialising.
 * That is a real cost — field editing has to work at the XML level — so the
 * justification should be demonstrated rather than believed.
 *
 * <p>This test opens the same real-world document through Apache POI, saves it
 * unchanged, and shows that parts come back different. Nothing here is a
 * criticism of POI: re-serialisation is what an object model does, and POI is
 * still the engine for <em>creating</em> documents. It is simply incompatible
 * with a requirement that saving an untouched document change nothing.
 *
 * <p>If POI ever becomes byte-stable, this test fails — and that is the signal
 * to revisit the decision, which is exactly what it is here for.
 */
class WhyNotReserialiseTest {

    private static byte[] fixture() {
        InputStream stream = WhyNotReserialiseTest.class
                .getResourceAsStream("/fixtures/leave-request-template.docx");
        if (stream == null) {
            throw new IllegalStateException("fixture missing from the test classpath");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The read already succeeded or threw; nothing useful to add.
            }
        }
    }

    @Test
    @DisplayName("an object-model round trip changes parts; the package-level one does not")
    void objectModelRoundTripIsNotByteStable() throws Exception {
        byte[] original = fixture();

        byte[] viaPoi;
        XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(original));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            viaPoi = out.toByteArray();
        } finally {
            document.close();
        }

        OoxmlPackage before = OoxmlPackage.read(original);
        OoxmlPackage afterPoi = OoxmlPackage.read(viaPoi);
        OoxmlPackage afterPackage = OoxmlPackage.read(before.write());

        List<String> changedByPoi = new ArrayList<String>();
        for (String part : before.partNames()) {
            if (!afterPoi.hasPart(part) || !java.util.Arrays.equals(
                    before.part(part), afterPoi.part(part))) {
                changedByPoi.add(part);
            }
        }

        assertThat(changedByPoi)
                .as("an object-model save is expected to alter or drop at least one part; if this "
                        + "is ever empty, revisit ADR 0007 — the reason for the package-level "
                        + "approach may no longer hold")
                .isNotEmpty();

        // ...and the package-level path changes nothing at all.
        for (String part : before.partNames()) {
            assertThat(afterPackage.part(part))
                    .as("part %s must be byte-identical through the package-level path", part)
                    .isEqualTo(before.part(part));
        }

        System.out.println("ADR 0007 evidence — parts altered or dropped by an object-model "
                + "round trip: " + changedByPoi);
    }
}
