package com.coreintra.app.api.org;

import com.coreintra.core.org.OrgUnit;
import java.util.function.Function;

/**
 * A node of the org tree on the wire.
 *
 * <p>The tree is returned as a flat, path-ordered list rather than as nested
 * children. Nesting would make the collection impossible to page — you cannot
 * cut a tree in half at row 50 — and §10 allows no offset pagination to escape
 * that with. The materialised {@code path} and {@code depth} give the client
 * everything it needs to re-nest what it received, and a walk that stops
 * halfway still returns a prefix of the same order.
 */
public class OrgUnitView {

    public static final Function<OrgUnit, OrgUnitView> MAPPER = new Function<OrgUnit, OrgUnitView>() {
        @Override
        public OrgUnitView apply(OrgUnit unit) {
            return from(unit);
        }
    };

    /**
     * Ordered by materialised path, which is depth-first tree order: a parent
     * always precedes its children, so a client can rebuild the tree from a
     * single pass and a page boundary never orphans a node it has already seen.
     */
    public static final Pages.Keys<OrgUnit> KEYS = new Pages.Keys<OrgUnit>() {
        @Override
        public String sortKey(OrgUnit unit) {
            return unit.path();
        }

        @Override
        public String id(OrgUnit unit) {
            return unit.id();
        }
    };

    private final String id;
    private final String companyId;
    private final String parentId;
    private final String code;
    private final String nameKo;
    private final String nameEn;
    private final String path;
    private final int depth;
    private final int sortOrder;
    private final boolean active;
    private final String createdAt;

    OrgUnitView(String id, String companyId, String parentId, String code, String nameKo,
            String nameEn, String path, int depth, int sortOrder, boolean active, String createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.parentId = parentId;
        this.code = code;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.path = path;
        this.depth = depth;
        this.sortOrder = sortOrder;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static OrgUnitView from(OrgUnit unit) {
        return new OrgUnitView(unit.id(), unit.companyId(), unit.parentId(), unit.code(),
                unit.nameKo(), unit.nameEn(), unit.path(), unit.depth(), unit.sortOrder(),
                unit.isActive(), unit.createdAt() == null ? null : unit.createdAt().toString());
    }

    /**
     * The tag an {@code If-Match} must carry.
     *
     * <p>The path is part of it, so a unit whose <em>ancestor</em> moved gets a
     * new tag too. That is the point: a subtree grant above it now reaches
     * somewhere else, and an editor holding the old tag is reasoning about an
     * org chart that no longer exists.
     */
    public static String tagOf(OrgUnit unit) {
        return OrgVersions.tag(unit.id(), unit.code(), unit.nameKo(), unit.nameEn(),
                unit.parentId(), unit.path(), Integer.valueOf(unit.depth()),
                Integer.valueOf(unit.sortOrder()), Boolean.valueOf(unit.isActive()));
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    /** Null at the top of a company's tree. */
    public String getParentId() {
        return parentId;
    }

    public String getCode() {
        return code;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getNameEn() {
        return nameEn;
    }

    /** Materialised path. Depth-first order; also what subtree grants resolve against. */
    public String getPath() {
        return path;
    }

    public int getDepth() {
        return depth;
    }

    /** Sibling order, chosen by an administrator. Not unique. */
    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    /** Real UTC. */
    public String getCreatedAt() {
        return createdAt;
    }
}
