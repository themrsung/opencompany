package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.service.ChartOfAccountsService;
import com.coreintra.accounting.service.NoSuchAccountingRecordException;
import com.coreintra.app.api.http.ETags;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.paging.Cursors;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chart of accounts.
 *
 * <p>Three properties of the tree are visible on this surface and none of them is negotiable here,
 * because they are decided in the domain: the type is the first character of the code and cannot
 * be changed, only leaves are postable, and an account is retired rather than deleted. So there is
 * no endpoint that changes a type, no endpoint that moves an account, and the {@code DELETE}
 * retires.
 *
 * <p>{@code classification} and {@code category} resolve by nearest ancestor. The list endpoint
 * returns both the account's own value and the resolved one, because "why is this in the investing
 * section" is answered by the difference between them and by nothing else on the screen.
 */
@RestController
@RequestMapping("/api/v1/accounting/books/{bookId}/accounts")
@AccountingEnabled
@Tag(name = "Accounting: chart of accounts",
        description = "The account tree. Only leaves are postable; accounts are retired, never "
                + "deleted, so prior-period reports never change behind anyone.")
public class ChartOfAccountsController {

    /** Charts run to hundreds of accounts, not thousands; a page of 200 is a whole small chart. */
    private static final int DEFAULT_PAGE = 200;
    private static final int MAX_PAGE = 1000;

    private final ChartOfAccountsService chart;
    private final CurrentPrincipal current;

    public ChartOfAccountsController(ChartOfAccountsService chart, CurrentPrincipal current) {
        this.chart = chart;
        this.current = current;
    }

    @GetMapping
    @Operation(summary = "The chart, by account code",
            description = "Requires accounting.account:read. Cursor-paginated by account code; "
                    + "pass the returned nextCursor back as ?cursor=. A null nextCursor is the "
                    + "only end-of-collection signal.")
    public CursorPage<AccountResponse> list(@PathVariable("bookId") String bookId,
            @Parameter(description = "Opaque; from the previous page's nextCursor.")
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        ChartOfAccounts snapshot = chart.chart(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate));
        int pageSize = Cursors.pageSize(limit, DEFAULT_PAGE, MAX_PAGE);
        Cursors.Position from = Cursors.decode(cursor);

        List<AccountResponse> page = new ArrayList<AccountResponse>();
        String nextCursor = null;
        for (Account account : snapshot.accounts()) {
            if (from != null && account.id().compareTo(from.sortKey()) <= 0) {
                continue;
            }
            if (page.size() == pageSize) {
                // One row beyond the page tells us there is a next page without claiming there is
                // one whenever the page happens to be full.
                nextCursor = Cursors.encode(page.get(page.size() - 1).getId(),
                        page.get(page.size() - 1).getId());
                break;
            }
            page.add(new AccountResponse(account, snapshot));
        }
        return new CursorPage<AccountResponse>(page, nextCursor);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "One account", description = "Requires accounting.account:read.")
    public ResponseEntity<AccountResponse> account(@PathVariable("bookId") String bookId,
            @PathVariable("accountId") String accountId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(businessDate);
        ChartOfAccounts snapshot = chart.chart(caller, bookId, asOf);
        Account account = snapshot.account(accountId);
        if (account == null) {
            // Consistent with the services: a missing row is a 404, not an empty 200.
            throw new NoSuchAccountingRecordException("account", accountId,
                    "book " + bookId + " has no account " + accountId);
        }
        return ResponseEntity.ok().eTag(etagOf(account))
                .body(new AccountResponse(account, snapshot));
    }

    @PostMapping
    @Operation(summary = "Open an account",
            description = "Requires accounting.account:create. The first character of the id is "
                    + "the type — 1 asset, 2 liability, 3 equity, 4 income, 5 expense — and is "
                    + "the only place the type is decided. Naming a parent makes that parent a "
                    + "subtotal, and it stops accepting postings from then on.")
    public ResponseEntity<AccountResponse> open(@PathVariable("bookId") String bookId,
            @Valid @RequestBody OpenAccountRequest request) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(request.getBusinessDate());
        chart.openAccount(caller, bookId, request.getId(), request.getParentId(),
                request.getNameKo(), request.getNameEn(), request.getCurrencyCode(),
                request.isContra(), asOf);
        if (request.getClassification() != null || request.getCategory() != null) {
            chart.describeAccount(caller, bookId, request.getId(),
                    ChartOfAccounts.Classification.fromStored(request.getClassification()),
                    ChartOfAccounts.Category.fromStored(request.getCategory()), asOf);
        }
        ChartOfAccounts snapshot = chart.chart(caller, bookId, asOf);
        Account created = snapshot.account(request.getId());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new AccountResponse(created, snapshot));
    }

    @PatchMapping("/{accountId}")
    @Operation(summary = "Rename an account, or change what it contributes to inheritance",
            description = "Requires accounting.account:update and If-Match. The type and the tree "
                    + "position are not editable by any path: retire the account and open another.")
    public ResponseEntity<AccountResponse> update(@PathVariable("bookId") String bookId,
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateAccountRequest request) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(request.getBusinessDate());
        ChartOfAccounts before = chart.chart(caller, bookId, asOf);
        Account existing = before.account(accountId);
        if (existing == null) {
            throw new NoSuchAccountingRecordException("account", accountId,
                    "book " + bookId + " has no account " + accountId);
        }
        ETags.require(ifMatch, etagOf(existing), "account");

        if (request.getNameKo() != null || request.getNameEn() != null) {
            chart.renameAccount(caller, bookId, accountId,
                    request.getNameKo() == null ? existing.nameKo() : request.getNameKo(),
                    request.getNameEn() == null ? existing.nameEn() : request.getNameEn(), asOf);
        }
        if (request.getClassification() != null || request.getCategory() != null) {
            chart.describeAccount(caller, bookId, accountId,
                    ChartOfAccounts.Classification.fromStored(request.getClassification()),
                    ChartOfAccounts.Category.fromStored(request.getCategory()), asOf);
        }
        ChartOfAccounts after = chart.chart(caller, bookId, asOf);
        Account updated = after.account(accountId);
        return ResponseEntity.ok().eTag(etagOf(updated))
                .body(new AccountResponse(updated, after));
    }

    @DeleteMapping("/{accountId}")
    @Operation(summary = "Retire an account",
            description = "Requires accounting.account:retire and If-Match. Historical figures are "
                    + "untouched and still reported; the account accepts no new postings.")
    public ResponseEntity<AccountResponse> retire(@PathVariable("bookId") String bookId,
            @PathVariable("accountId") String accountId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(businessDate);
        ChartOfAccounts before = chart.chart(caller, bookId, asOf);
        Account existing = before.account(accountId);
        if (existing == null) {
            throw new NoSuchAccountingRecordException("account", accountId,
                    "book " + bookId + " has no account " + accountId);
        }
        ETags.require(ifMatch, etagOf(existing), "account");
        chart.retireAccount(caller, bookId, accountId, asOf);

        ChartOfAccounts after = chart.chart(caller, bookId, asOf);
        Account retired = after.account(accountId);
        return ResponseEntity.ok().eTag(etagOf(retired)).body(new AccountResponse(retired, after));
    }

    /**
     * An account's entity tag.
     *
     * <p>Derived from the two things about an account that can change after it is opened — whether
     * it has been retired, and whether it has acquired a child — plus its name. The name is in
     * there because a rename is the concurrent edit this header exists to catch: two people
     * relabelling the same account is the ordinary case, not the exotic one.
     */
    private static String etagOf(Account account) {
        long version = (account.isRetired() ? 2L : 0L) + (account.hasChildren() ? 1L : 0L);
        return ETags.of(account.id() + '|' + account.nameKo() + '|' + account.nameEn(), version);
    }

    // ------------------------------------------------------------------
    // Wire types
    // ------------------------------------------------------------------

    /** One account, with both its own attributes and the ones it resolves to. */
    public static class AccountResponse {
        private final String id;
        private final String parentId;
        private final String type;
        private final String nameKo;
        private final String nameEn;
        private final String currencyCode;
        private final boolean contra;
        private final boolean postable;
        private final boolean retired;
        private final String postingRefusalReason;
        private final String ownClassification;
        private final String ownCategory;
        private final String classification;
        private final String category;

        AccountResponse(Account account, ChartOfAccounts snapshot) {
            this.id = account.id();
            this.parentId = account.parentId();
            this.type = account.type().name();
            this.nameKo = account.nameKo();
            this.nameEn = account.nameEn();
            this.currencyCode = account.currencyCode();
            this.contra = account.isContra();
            this.postable = account.isPostable();
            this.retired = account.isRetired();
            this.postingRefusalReason = account.postingRefusalReason();
            ChartOfAccounts.Attributes own = snapshot.ownAttributes(account.id());
            this.ownClassification = name(own.classification());
            this.ownCategory = name(own.category());
            this.classification = name(snapshot.classificationOf(account.id()));
            this.category = name(snapshot.categoryOf(account.id()));
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }

        public String getId() {
            return id;
        }

        public String getParentId() {
            return parentId;
        }

        /** ASSET, LIABILITY, EQUITY, INCOME or EXPENSE. Immutable once the account exists. */
        public String getType() {
            return type;
        }

        public String getNameKo() {
            return nameKo;
        }

        public String getNameEn() {
            return nameEn;
        }

        /** Null means the book's base currency. */
        public String getCurrencyCode() {
            return currencyCode;
        }

        public boolean isContra() {
            return contra;
        }

        public boolean isPostable() {
            return postable;
        }

        public boolean isRetired() {
            return retired;
        }

        /** Why a posting to this account would be refused, or null when it would not be. */
        public String getPostingRefusalReason() {
            return postingRefusalReason;
        }

        /** Set on this account itself. Null means "inherit". */
        public String getOwnClassification() {
            return ownClassification;
        }

        public String getOwnCategory() {
            return ownCategory;
        }

        /** In force here, after nearest-ancestor resolution. */
        public String getClassification() {
            return classification;
        }

        public String getCategory() {
            return category;
        }
    }

    /** Opening an account. */
    public static class OpenAccountRequest {
        @NotBlank
        private String id;
        private String parentId;
        @NotBlank
        private String nameKo;
        private String nameEn;
        private String currencyCode;
        private boolean contra;
        private String classification;
        private String category;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getId() {
            return id;
        }

        public void setId(String value) {
            this.id = value;
        }

        public String getParentId() {
            return parentId;
        }

        public void setParentId(String value) {
            this.parentId = value;
        }

        public String getNameKo() {
            return nameKo;
        }

        public void setNameKo(String value) {
            this.nameKo = value;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String value) {
            this.nameEn = value;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(String value) {
            this.currencyCode = value;
        }

        public boolean isContra() {
            return contra;
        }

        public void setContra(boolean value) {
            this.contra = value;
        }

        public String getClassification() {
            return classification;
        }

        public void setClassification(String value) {
            this.classification = value;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String value) {
            this.category = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }

    /** Renaming, or changing what an account contributes to inheritance. */
    public static class UpdateAccountRequest {
        private String nameKo;
        private String nameEn;
        private String classification;
        private String category;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getNameKo() {
            return nameKo;
        }

        public void setNameKo(String value) {
            this.nameKo = value;
        }

        public String getNameEn() {
            return nameEn;
        }

        public void setNameEn(String value) {
            this.nameEn = value;
        }

        public String getClassification() {
            return classification;
        }

        public void setClassification(String value) {
            this.classification = value;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String value) {
            this.category = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }
}
