package com.coreintra.app.api.documents;

import com.coreintra.app.api.http.ETags;
import com.coreintra.compat.Immutables;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A template on the wire, with its versions and its field manifest.
 *
 * <p>{@code builtIn} is the seeded/forked distinction and it matters to the UI:
 * a seeded template can be restored to factory state, and a forked one cannot,
 * because there is no factory state to restore it to (§6.8).
 */
public class TemplateView {

    static final Function<DocumentTemplateEntity, TemplateView> MAPPER =
            new Function<DocumentTemplateEntity, TemplateView>() {
                @Override
                public TemplateView apply(DocumentTemplateEntity template) {
                    return from(template);
                }
            };

    /** Ordered by code: it is the stable handle a seeded template is found by. */
    static final DocumentPages.Keys<DocumentTemplateEntity> KEYS =
            new DocumentPages.Keys<DocumentTemplateEntity>() {
                @Override
                public String sortKey(DocumentTemplateEntity template) {
                    return template.code() == null ? "" : template.code();
                }

                @Override
                public String id(DocumentTemplateEntity template) {
                    return template.id();
                }
            };

    /** One published version. Documents pin to these and never follow later ones. */
    public static class Version {
        private final String templateId;
        private final int versionNo;
        private final boolean hasApprovalBlock;
        private final Integer supersedesVersionNo;
        private final String publishedByAccountId;
        private final String publishedAt;
        private final String createdAt;

        Version(DocumentTemplateVersionEntity version) {
            this.templateId = version.templateId();
            this.versionNo = version.versionNo().intValue();
            this.hasApprovalBlock = version.hasApprovalBlock();
            this.supersedesVersionNo = version.supersedesVersionNo();
            this.publishedByAccountId = version.publishedByAccountId();
            this.publishedAt = version.publishedAt() == null
                    ? null : version.publishedAt().toWireString();
            this.createdAt = String.valueOf(version.createdAt());
        }

        public String getTemplateId() {
            return templateId;
        }

        public int getVersionNo() {
            return versionNo;
        }

        /** True when the docx carries the 결재란 control that the approval line renders into. */
        public boolean isHasApprovalBlock() {
            return hasApprovalBlock;
        }

        public Integer getSupersedesVersionNo() {
            return supersedesVersionNo;
        }

        public String getPublishedByAccountId() {
            return publishedByAccountId;
        }

        /** Business-time wire form. */
        public String getPublishedAt() {
            return publishedAt;
        }

        /** UTC, recorded separately. */
        public String getCreatedAt() {
            return createdAt;
        }
    }

    /** One entry of the field manifest: a description of a content control in the docx. */
    public static class Field {
        private final String tag;
        private final String type;
        private final String labelKo;
        private final String labelEn;
        private final boolean required;
        private final String helpKo;
        private final String helpEn;

        Field(FieldDefinition definition) {
            this.tag = definition.tag();
            this.type = definition.type().name();
            this.labelKo = definition.labelKo();
            this.labelEn = definition.labelEn();
            this.required = definition.isRequired();
            this.helpKo = definition.helpKo();
            this.helpEn = definition.helpEn();
        }

        /** The {@code w:sdt} tag. Content controls survive Word, LibreOffice and 한글. */
        public String getTag() {
            return tag;
        }

        public String getType() {
            return type;
        }

        /** Required: Korean is the default locale, so an English-only label is a blank caption. */
        public String getLabelKo() {
            return labelKo;
        }

        public String getLabelEn() {
            return labelEn;
        }

        /** Checked at submission, not at save: drafts are saved half-filled all day. */
        public boolean isRequired() {
            return required;
        }

        public String getHelpKo() {
            return helpKo;
        }

        public String getHelpEn() {
            return helpEn;
        }
    }

    private final String id;
    private final String companyId;
    private final String code;
    private final String documentType;
    private final String nameKo;
    private final String nameEn;
    private final Integer currentVersionNo;
    private final boolean builtIn;
    private final boolean active;
    private final String createdByAccountId;
    private final String createdAt;
    private final String retiredAt;

    TemplateView(DocumentTemplateEntity template) {
        this.id = template.id();
        this.companyId = template.companyId();
        this.code = template.code();
        this.documentType = template.documentType();
        this.nameKo = template.nameKo();
        this.nameEn = template.nameEn();
        this.currentVersionNo = template.currentVersionNo();
        this.builtIn = template.isBuiltIn();
        this.active = template.isActive();
        this.createdByAccountId = template.createdByAccountId();
        this.createdAt = String.valueOf(template.createdAt());
        this.retiredAt = template.retiredAt() == null ? null : String.valueOf(template.retiredAt());
    }

    public static TemplateView from(DocumentTemplateEntity template) {
        return new TemplateView(template);
    }

    /** The tag an {@code If-Match} on this template must carry. Moves when a version publishes. */
    public static String tagOf(DocumentTemplateEntity template) {
        Integer version = template.currentVersionNo();
        return ETags.of(template.id(), version == null ? 0L : version.longValue());
    }

    static List<Version> versions(List<DocumentTemplateVersionEntity> rows) {
        List<Version> views = new ArrayList<Version>(rows.size());
        for (DocumentTemplateVersionEntity row : rows) {
            views.add(new Version(row));
        }
        return Immutables.copyOf(views);
    }

    static List<Field> fields(DocumentFieldSchema schema) {
        List<Field> views = new ArrayList<Field>();
        for (FieldDefinition definition : schema.fields()) {
            views.add(new Field(definition));
        }
        return Immutables.copyOf(views);
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    /** The stable handle. A seeded 휴가신청서 is found by its code, not by its name. */
    public String getCode() {
        return code;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getNameEn() {
        return nameEn;
    }

    /** Null until the first version is published. A template with no version drafts nothing. */
    public Integer getCurrentVersionNo() {
        return currentVersionNo;
    }

    /** Seeded by us. Only a built-in template can be restored to factory state. */
    public boolean isBuiltIn() {
        return builtIn;
    }

    public boolean isActive() {
        return active;
    }

    public String getCreatedByAccountId() {
        return createdByAccountId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getRetiredAt() {
        return retiredAt;
    }
}
