package com.coreintra.core.org;

import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A node in the org tree — 본부, 팀, 실, whatever the client calls them.
 *
 * <h2>Why there is a materialised path</h2>
 *
 * <p>{@link com.coreintra.core.permission.PermissionScope#ORG_UNIT_SUBTREE} is
 * evaluated on every request that touches a scoped row. Walking parent pointers
 * per check would make authorisation an N+1 against the hottest path in the
 * system. {@code path} holds {@code /hq/support/finance/} so a subtree test is
 * a prefix match on an indexed column, and a subtree query is one statement.
 *
 * <p>The path is derived state and is rebuilt by {@link #attachTo}. It is never
 * edited directly, and a move rewrites the paths of the whole subtree.
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    /**
     * Materialised path, {@code /hq/support/finance/}, always slash-delimited
     * and always slash-terminated so a prefix match cannot half-match a sibling
     * whose code shares a prefix ({@code /hq/sales/} vs {@code /hq/salesops/}).
     */
    @Column(name = "path", nullable = false, length = 1000)
    private String path;

    @Column(name = "depth", nullable = false)
    private int depth;

    /** Display order among siblings. Client-defined; never inferred from the name. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected OrgUnit() {
    }

    public OrgUnit(String id, String companyId, String code, String nameKo) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.nameKo = nameKo;
        this.path = "/" + code + "/";
        this.depth = 0;
        this.createdAt = OffsetDateTime.now();
    }

    /** Re-parents this unit and recomputes its path. Callers must then fix descendants. */
    public void attachTo(OrgUnit parent) {
        if (parent == null) {
            this.parentId = null;
            this.path = "/" + code + "/";
            this.depth = 0;
            return;
        }
        if (parent.path.contains("/" + this.code + "/")) {
            throw new IllegalArgumentException(
                    "attaching " + code + " under " + parent.code + " would create a cycle");
        }
        this.parentId = parent.id;
        this.path = parent.path + code + "/";
        this.depth = parent.depth + 1;
    }

    /** True when {@code other} is this unit or lies beneath it. Reflexive, as "subtree" implies. */
    public boolean contains(OrgUnit other) {
        return other != null && other.path.startsWith(this.path);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String parentId() {
        return parentId;
    }

    public String code() {
        return code;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public void rename(String nameKo, String nameEn) {
        this.nameKo = nameKo;
        this.nameEn = nameEn;
    }

    public String path() {
        return path;
    }

    public int depth() {
        return depth;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int value) {
        this.sortOrder = value;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
