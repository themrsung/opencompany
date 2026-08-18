package com.coreintra.app.api.org;

import com.coreintra.core.org.Company;
import java.util.function.Function;

/**
 * A legal entity on the wire.
 *
 * <p>A DTO rather than the entity itself, for two reasons that both bite in
 * production. A JPA entity on a controller signature puts the column layout
 * into the published contract, so a schema change becomes a breaking API
 * change; and it hands the serialiser a managed object, so Jackson triggers
 * lazy loads inside the response writer, outside the transaction and outside
 * anything the evaluator authorised.
 *
 * <p>{@code establishedOn} and {@code createdAt} are strings. {@code createdAt}
 * is a real UTC instant and never a business one — §2 keeps the two apart, and
 * rendering it as though it were a business instant is precisely the conflation
 * that makes an audit trail unreadable a year later.
 */
public class CompanyView {

    /** For {@link Pages#page}, which maps only the rows it returns. */
    public static final Function<Company, CompanyView> MAPPER = new Function<Company, CompanyView>() {
        @Override
        public CompanyView apply(Company company) {
            return from(company);
        }
    };

    /** Ordered by the code an administrator typed, not by the generated id. */
    public static final Pages.Keys<Company> KEYS = new Pages.Keys<Company>() {
        @Override
        public String sortKey(Company company) {
            return company.code();
        }

        @Override
        public String id(Company company) {
            return company.id();
        }
    };

    private final String id;
    private final String code;
    private final String nameKo;
    private final String nameEn;
    private final String kind;
    private final String parentCompanyId;
    private final String baseCurrencyCode;
    private final String businessRegistrationNumber;
    private final String establishedOn;
    private final boolean active;
    private final String createdAt;

    CompanyView(String id, String code, String nameKo, String nameEn, String kind,
            String parentCompanyId, String baseCurrencyCode, String businessRegistrationNumber,
            String establishedOn, boolean active, String createdAt) {
        this.id = id;
        this.code = code;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.kind = kind;
        this.parentCompanyId = parentCompanyId;
        this.baseCurrencyCode = baseCurrencyCode;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.establishedOn = establishedOn;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static CompanyView from(Company company) {
        return new CompanyView(company.id(), company.code(), company.nameKo(), company.nameEn(),
                company.kind() == null ? null : company.kind().name(), company.parentCompanyId(),
                company.baseCurrencyCode(), company.businessRegistrationNumber(),
                company.establishedOn() == null ? null : company.establishedOn().toString(),
                company.isActive(),
                company.createdAt() == null ? null : company.createdAt().toString());
    }

    /** The {@code ETag} an {@code If-Match} on this company must carry. */
    public static String tagOf(Company company) {
        return OrgVersions.tag(company.id(), company.code(), company.nameKo(), company.nameEn(),
                company.kind(), company.parentCompanyId(), company.baseCurrencyCode(),
                company.businessRegistrationNumber(), company.establishedOn(),
                Boolean.valueOf(company.isActive()));
    }

    public String getId() {
        return id;
    }

    /** Client-chosen and stable; the id is generated and means nothing to anyone. */
    public String getCode() {
        return code;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getNameEn() {
        return nameEn;
    }

    /** {@code HEAD_OFFICE} 본사, {@code BRANCH} 지사, {@code SUBSIDIARY} 자회사. */
    public String getKind() {
        return kind;
    }

    public String getParentCompanyId() {
        return parentCompanyId;
    }

    public String getBaseCurrencyCode() {
        return baseCurrencyCode;
    }

    public String getBusinessRegistrationNumber() {
        return businessRegistrationNumber;
    }

    public String getEstablishedOn() {
        return establishedOn;
    }

    /** False once deactivated. Nothing in this system is deleted. */
    public boolean isActive() {
        return active;
    }

    /** Real UTC, from the {@code created_at} column. Not a business instant. */
    public String getCreatedAt() {
        return createdAt;
    }
}
