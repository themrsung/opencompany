package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One link in a client-editable fallback chain (brief 6.9).
 *
 * <p>Ordered by {@link #position} and consulted in order: the first installed family
 * that covers the script wins, and the substitution is then named in the warning and
 * recorded in the render metadata. Without the ordinal, "prefer Pretendard, then Noto"
 * cannot be expressed - a set has no such thing.
 */
@Entity
@Table(name = "font_substitution")
public class FontSubstitutionRuleEntity {

    /** Whether the rule is keyed by a requested family or by an ISO 15924 script code. */
    public enum Scope {

        /** "when a document asks for 함초롬바탕, try these". */
        FAMILY,

        /** "when text is in Hang and nothing installed covers it, try these". */
        SCRIPT
    }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 8)
    private Scope scope;

    @Column(name = "scope_key", nullable = false, length = 200)
    private String scopeKey;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "fallback_family", nullable = false, length = 200)
    private String fallbackFamily;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected FontSubstitutionRuleEntity() {
    }

    public FontSubstitutionRuleEntity(String id, String companyId, Scope scope, String scopeKey,
            int position, String fallbackFamily) {
        this.id = id;
        this.companyId = companyId;
        this.scope = scope;
        this.scopeKey = scopeKey;
        this.position = position;
        this.fallbackFamily = fallbackFamily;
        this.createdAt = OffsetDateTime.now();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public Scope scope() {
        return scope;
    }

    public String scopeKey() {
        return scopeKey;
    }

    public int position() {
        return position;
    }

    public String fallbackFamily() {
        return fallbackFamily;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
