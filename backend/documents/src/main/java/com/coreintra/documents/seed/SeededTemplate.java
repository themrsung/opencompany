package com.coreintra.documents.seed;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.schema.DocumentFieldSchema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One factory template: its identity, its field manifest, and one body per
 * locale.
 *
 * <h2>Why the bodies are a map rather than two fields</h2>
 *
 * <p>Most templates have a Korean and an English body. 회의록 and 이사회안건 also
 * carry an mdv companion, because §6.10 asks the seeded set to demonstrate the
 * format on first run and a chart cannot demonstrate itself inside a Word file
 * this system cannot render.
 *
 * <p>Those companions are keyed {@code ko-x-mdv} and {@code en-x-mdv}: valid
 * BCP-47 private-use subtags, which matters because
 * {@code document_template_body} is keyed by {@code (template, version, locale)}
 * and therefore holds exactly one body per locale. The alternative — a second
 * template family — would have detached the chart from the form it belongs to.
 */
public final class SeededTemplate {

    /** The Korean body every seeded template has. */
    public static final String LOCALE_KO = "ko";

    /** The English body every seeded template has. */
    public static final String LOCALE_EN = "en";

    /** The mdv companion, where there is one. */
    public static final String LOCALE_KO_MDV = "ko-x-mdv";

    public static final String LOCALE_EN_MDV = "en-x-mdv";

    private final String code;
    private final String documentType;
    private final String nameKo;
    private final String nameEn;
    private final DocumentFieldSchema schema;
    private final Map<String, InternalDoc> bodies;

    SeededTemplate(String code, String documentType, String nameKo, String nameEn,
            DocumentFieldSchema schema, Map<String, InternalDoc> bodies) {
        this.code = code;
        this.documentType = documentType;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.schema = schema;
        this.bodies = Immutables.mapCopyOf(
                bodies == null ? new LinkedHashMap<String, InternalDoc>() : bodies);
    }

    /** The stable handle. A seeded template is found by this, never by name. */
    public String code() {
        return code;
    }

    public String documentType() {
        return documentType;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public DocumentFieldSchema schema() {
        return schema;
    }

    /** Locale to body, in a fixed order so the install is reproducible. */
    public Map<String, InternalDoc> bodies() {
        return bodies;
    }

    public InternalDoc body(String locale) {
        return bodies.get(locale);
    }
}
