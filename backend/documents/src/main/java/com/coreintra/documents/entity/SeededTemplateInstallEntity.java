package com.coreintra.documents.entity;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * A record that a factory template was installed, and at which catalogue
 * revision.
 *
 * <h2>Why this is not derived from {@code document_template}</h2>
 *
 * <p>"Has this company got the seeded 지출결의서?" can be answered from the
 * template table. "Has it got <em>this</em> 지출결의서?" cannot, and that is the
 * question the installer has to answer on every boot.
 *
 * <p>Without the revision, shipping a corrected factory template leaves two
 * kinds of installation that are indistinguishable: one whose 지출결의서 is the
 * old one, and one whose 지출결의서 the client edited deliberately. Overwriting
 * either would be wrong. With it, the installer publishes a new version only
 * where the recorded revision is behind, and a client's own edits — which are
 * later versions of the same template — are never touched.
 */
@Entity
@Table(name = "document_template_seed")
@IdClass(SeededTemplateInstallId.class)
public class SeededTemplateInstallEntity {

    @Id
    @Column(name = "company_id", length = 36)
    private String companyId;

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "catalogue_revision", nullable = false)
    private Integer catalogueRevision;

    @Column(name = "installed_version_no", nullable = false)
    private Integer installedVersionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected SeededTemplateInstallEntity() {
    }

    public SeededTemplateInstallEntity(String companyId, String code, String templateId,
            int catalogueRevision, int installedVersionNo) {
        this.companyId = companyId;
        this.code = code;
        this.templateId = templateId;
        this.catalogueRevision = Integer.valueOf(catalogueRevision);
        this.installedVersionNo = Integer.valueOf(installedVersionNo);
    }

    /** Records that a newer factory revision has been published as a new version. */
    public void upgradedTo(int catalogueRevision, int versionNo, OffsetDateTime at) {
        this.catalogueRevision = Integer.valueOf(catalogueRevision);
        this.installedVersionNo = Integer.valueOf(versionNo);
        this.updatedAt = at;
    }

    public String companyId() {
        return companyId;
    }

    public String code() {
        return code;
    }

    public String templateId() {
        return templateId;
    }

    public Integer catalogueRevision() {
        return catalogueRevision;
    }

    /** The version this revision was published as. The factory state to restore to. */
    public Integer installedVersionNo() {
        return installedVersionNo;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
