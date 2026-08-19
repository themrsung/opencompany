package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.Currency;
import com.coreintra.accounting.persistence.AccountingClientRow;
import com.coreintra.accounting.persistence.BookRow;
import com.coreintra.accounting.service.BookService;
import com.coreintra.app.api.http.ETags;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Books, the units they balance in, and the counterparties they trade with.
 *
 * <p>The three sit together because they are the same decision: what a set of books <em>is</em>,
 * before anything can be posted into it. They carry separate permissions all the same
 * ({@code accounting.book}, {@code accounting.currency}, {@code accounting.client}), because
 * defining a unit of account and registering a customer are different amounts of authority.
 *
 * <p>Retirement is a {@code DELETE} that deletes nothing. There is no hard delete anywhere in this
 * module: a retired book, currency or 거래처 keeps every historical figure it carried and stops
 * accepting new ones. The verb is {@code DELETE} because that is what a client expects to call to
 * take something out of use, and the response body says {@code retired} so nobody is in doubt.
 */
@RestController
@RequestMapping("/api/v1/accounting")
@AccountingEnabled
@Tag(name = "Accounting: books", description = "Sets of books, their units of account, and their "
        + "거래처. Amounts anywhere in this API are exact decimal strings, never JSON numbers.")
public class AccountingBooksController {

    private final BookService books;
    private final CurrentPrincipal current;

    public AccountingBooksController(BookService books, CurrentPrincipal current) {
        this.books = books;
        this.current = current;
    }

    // ------------------------------------------------------------------
    // Books
    // ------------------------------------------------------------------

    @GetMapping("/books")
    @Operation(summary = "The books of one company",
            description = "Requires accounting.book:read on the company.")
    public List<BookResponse> listBooks(
            @RequestParam("companyId") String companyId,
            @Parameter(description = "The business date the permission is resolved as of. "
                    + "Defaults to today.")
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(businessDate);
        List<BookResponse> found = new ArrayList<BookResponse>();
        for (BookRow row : books.booksOf(caller, companyId, asOf)) {
            found.add(new BookResponse(row));
        }
        return found;
    }

    @GetMapping("/books/{bookId}")
    @Operation(summary = "One book", description = "Requires accounting.book:read.")
    public ResponseEntity<BookResponse> book(@PathVariable("bookId") String bookId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        BookRow row = books.book(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate));
        return ResponseEntity.ok().eTag(etagOf(row)).body(new BookResponse(row));
    }

    @PostMapping("/books")
    @Operation(summary = "Open a set of books",
            description = "Requires accounting.book:manage on the company. The base currency is "
                    + "written first: a book whose base currency does not exist cannot report "
                    + "anything. KRW and USD are seeded alongside it and are both editable.")
    public ResponseEntity<BookResponse> openBook(@Valid @RequestBody OpenBookRequest request) {
        BookRow row = books.openBook(current.require(), request.getCompanyId(), request.getName(),
                request.getBaseCurrency().toDomain(),
                Amounts.businessDateOrToday(request.getBusinessDate()));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(row))
                .body(new BookResponse(row));
    }

    @DeleteMapping("/books/{bookId}")
    @Operation(summary = "Retire a book",
            description = "Requires accounting.book:manage and If-Match. Nothing is deleted: the "
                    + "book stops accepting new entries and keeps every figure it holds.")
    public ResponseEntity<BookResponse> retireBook(@PathVariable("bookId") String bookId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        PermissionPrincipal caller = current.require();
        LocalDate asOf = Amounts.businessDateOrToday(businessDate);
        BookRow before = books.book(caller, bookId, asOf);
        ETags.require(ifMatch, etagOf(before), "book");
        books.retireBook(caller, bookId, asOf);
        BookRow after = books.book(caller, bookId, asOf);
        return ResponseEntity.ok().eTag(etagOf(after)).body(new BookResponse(after));
    }

    // ------------------------------------------------------------------
    // Currencies
    // ------------------------------------------------------------------

    @GetMapping("/books/{bookId}/currencies")
    @Operation(summary = "The units of account this book can still use",
            description = "Requires accounting.currency:read. A unit of account need not be money "
                    + "— a commodity or a share count is a currency here.")
    public List<CurrencyResponse> currencies(@PathVariable("bookId") String bookId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        List<CurrencyResponse> found = new ArrayList<CurrencyResponse>();
        for (Currency currency : books.currencies(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate))) {
            found.add(new CurrencyResponse(currency));
        }
        return found;
    }

    @PutMapping("/books/{bookId}/currencies/{code}")
    @Operation(summary = "Define a unit of account, or re-describe one",
            description = "Requires accounting.currency:manage. displayDecimals is presentation "
                    + "only: it never limits or rescales what is stored.")
    public CurrencyResponse defineCurrency(@PathVariable("bookId") String bookId,
            @PathVariable("code") String code,
            @Valid @RequestBody CurrencyRequest request) {
        // The code comes from the path, not the body: it is what every posting points at, and a
        // body that disagreed with the URL would have to be resolved one way or the other.
        Currency currency = request.toDomain(code);
        books.defineCurrency(current.require(), bookId, currency,
                Amounts.businessDateOrToday(request.getBusinessDate()));
        return new CurrencyResponse(currency);
    }

    @DeleteMapping("/books/{bookId}/currencies/{code}")
    @Operation(summary = "Retire a unit of account",
            description = "Requires accounting.currency:manage. Historical postings keep it: "
                    + "those amounts were denominated in something.")
    public ResponseEntity<Void> retireCurrency(@PathVariable("bookId") String bookId,
            @PathVariable("code") String code,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        books.retireCurrency(current.require(), bookId, code,
                Amounts.businessDateOrToday(businessDate));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // 거래처
    // ------------------------------------------------------------------

    @GetMapping("/books/{bookId}/clients")
    @Operation(summary = "The 거래처 of this book",
            description = "Requires accounting.client:read. This is the counterparty list of the "
                    + "business, which is why reading it is a permission of its own.")
    public List<ClientResponse> clients(@PathVariable("bookId") String bookId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        List<ClientResponse> found = new ArrayList<ClientResponse>();
        for (AccountingClientRow row : books.clients(current.require(), bookId,
                Amounts.businessDateOrToday(businessDate))) {
            found.add(new ClientResponse(row));
        }
        return found;
    }

    @PostMapping("/books/{bookId}/clients")
    @Operation(summary = "Register a 거래처",
            description = "Requires accounting.client:manage. This is the dimension receivables "
                    + "ageing and prepaid schedules are grouped by.")
    public ResponseEntity<ClientResponse> registerClient(@PathVariable("bookId") String bookId,
            @Valid @RequestBody ClientRequest request) {
        AccountingClientRow row = books.registerClient(current.require(), bookId,
                request.getName(), request.getNote(),
                Amounts.businessDateOrToday(request.getBusinessDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ClientResponse(row));
    }

    @DeleteMapping("/books/{bookId}/clients/{clientId}")
    @Operation(summary = "Retire a 거래처",
            description = "Requires accounting.client:manage. A counterparty you no longer trade "
                    + "with still owes what it owed, so this hides rather than deletes.")
    public ResponseEntity<Void> retireClient(@PathVariable("bookId") String bookId,
            @PathVariable("clientId") String clientId,
            @RequestParam(value = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        books.retireClient(current.require(), clientId,
                Amounts.businessDateOrToday(businessDate));
        return ResponseEntity.noContent().build();
    }

    /**
     * A book's entity tag.
     *
     * <p>Books have no version column, and adding one is a migration this change does not need:
     * the only mutable thing about a book is whether it has been retired, so that <em>is</em> its
     * version. If a book ever grows an editable field, this becomes a real counter rather than a
     * boolean, and the tag changing shape is exactly what should happen then.
     */
    private static String etagOf(BookRow row) {
        return ETags.of(row.id(), row.isRetired() ? 1L : 0L);
    }

    // ------------------------------------------------------------------
    // Wire types
    // ------------------------------------------------------------------

    /** A set of books. */
    public static class BookResponse {
        private final String id;
        private final String companyId;
        private final String name;
        private final String baseCurrencyCode;
        private final boolean retired;

        BookResponse(BookRow row) {
            this.id = row.id();
            this.companyId = row.companyId();
            this.name = row.name();
            this.baseCurrencyCode = row.baseCurrencyCode();
            this.retired = row.isRetired();
        }

        public String getId() {
            return id;
        }

        public String getCompanyId() {
            return companyId;
        }

        public String getName() {
            return name;
        }

        /** The unit every entry in this book balances in. */
        public String getBaseCurrencyCode() {
            return baseCurrencyCode;
        }

        public boolean isRetired() {
            return retired;
        }
    }

    /** A unit of account. {@code displayDecimals} is presentation and nothing else. */
    public static class CurrencyResponse {
        private final String code;
        private final String nameKo;
        private final String nameEn;
        private final String symbol;
        private final int displayDecimals;

        CurrencyResponse(Currency currency) {
            this.code = currency.code();
            this.nameKo = currency.nameKo();
            this.nameEn = currency.nameEn();
            this.symbol = currency.symbol();
            this.displayDecimals = currency.displayDecimals();
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

        public String getSymbol() {
            return symbol;
        }

        /**
         * How many decimals a screen shows. Never how many are stored.
         *
         * <p>An integer rather than a string because it is a count of digits, not a quantity of
         * anything. The rule that amounts are strings is about values that can lose precision.
         */
        public int getDisplayDecimals() {
            return displayDecimals;
        }
    }

    /** A 거래처. */
    public static class ClientResponse {
        private final String id;
        private final String name;
        private final String note;
        private final boolean retired;

        ClientResponse(AccountingClientRow row) {
            this.id = row.id();
            this.name = row.name();
            this.note = row.note();
            this.retired = row.isRetired();
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getNote() {
            return note;
        }

        public boolean isRetired() {
            return retired;
        }
    }

    /** Opening a set of books. */
    public static class OpenBookRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String name;
        private CurrencyRequest baseCurrency;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }

        /** The unit this book balances in. */
        public CurrencyRequest getBaseCurrency() {
            return baseCurrency;
        }

        public void setBaseCurrency(CurrencyRequest value) {
            this.baseCurrency = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }

    /** Defining a unit of account. */
    public static class CurrencyRequest {
        private String code;
        @NotBlank
        private String nameKo;
        private String nameEn;
        private String symbol;
        private int displayDecimals;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
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

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String value) {
            this.symbol = value;
        }

        public int getDisplayDecimals() {
            return displayDecimals;
        }

        public void setDisplayDecimals(int value) {
            this.displayDecimals = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }

        Currency toDomain() {
            return toDomain(code);
        }

        Currency toDomain(String currencyCode) {
            return new Currency(currencyCode, nameKo, nameEn, symbol, displayDecimals);
        }
    }

    /** Registering a 거래처. */
    public static class ClientRequest {
        @NotBlank
        private String name;
        private String note;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String value) {
            this.note = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }
}
