package com.coreintra.accounting.persistence;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.ChartOfAccounts;
import java.io.Serializable;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * The persistent form of an {@link Account} in a book's chart of accounts.
 *
 * <h2>The key is (book, id) and the id is the account code</h2>
 *
 * <p>Not a UUID. The type is read from the first character of the code, here and in
 * {@code AccountType.fromAccountId}, which is what makes the type immutable: to change it you
 * would have to change the id, and the id is what every posting points at. Codes repeat across
 * books, so the book is part of the key.
 *
 * <h2>has_children is a database fact, not a cache</h2>
 *
 * <p>{@code journal_posting} carries a copy of this flag and a foreign key that forces the two to
 * agree, with the copy pinned to FALSE. So giving an account a child stops it accepting postings
 * without any application code being involved - and flipping the flag on an account that already
 * has postings is refused, because a parent holding postings makes its own subtotal wrong and no
 * report can tell you that happened.
 */
@Entity
@Table(name = "account")
@IdClass(AccountRow.Key.class)
public class AccountRow {

    @Id
    @Column(name = "book_id", nullable = false, length = 36)
    private String bookId;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    /**
     * Kept in step with the id prefix by a database CHECK. Stored rather than derived because a
     * report grouping by type should not have to parse a string to do it.
     */
    @Column(name = "type", nullable = false, length = 12)
    private String type;

    @Column(name = "name_ko", nullable = false, length = 200)
    private String nameKo;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    /** Null means the book's base currency. */
    @Column(name = "currency_code", length = 12)
    private String currencyCode;

    @Column(name = "contra", nullable = false)
    private boolean contra;

    /** Null means "inherit from the nearest ancestor that sets one" - never "none". */
    @Column(name = "classification", length = 16)
    private String classification;

    /** Null means "inherit from the nearest ancestor that sets one" - never "none". */
    @Column(name = "category", length = 24)
    private String category;

    @Column(name = "has_children", nullable = false)
    private boolean hasChildren;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AccountRow() {
    }

    /** Built from the domain object, so the id, type and name have already been validated. */
    public AccountRow(String bookId, Account account) {
        this.bookId = bookId;
        this.id = account.id();
        this.parentId = account.parentId();
        this.type = account.type().name();
        this.nameKo = account.nameKo();
        this.nameEn = account.nameEn();
        this.currencyCode = account.currencyCode();
        this.contra = account.isContra();
        this.hasChildren = account.hasChildren();
        this.createdAt = OffsetDateTime.now();
    }

    public String bookId() {
        return bookId;
    }

    public String id() {
        return id;
    }

    public String parentId() {
        return parentId;
    }

    public String type() {
        return type;
    }

    public String nameKo() {
        return nameKo;
    }

    public String nameEn() {
        return nameEn;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public boolean isContra() {
        return contra;
    }

    public String classification() {
        return classification;
    }

    public String category() {
        return category;
    }

    /** What this account sets for itself. Anything left null keeps inheriting. */
    public void describeAs(ChartOfAccounts.Classification newClassification,
            ChartOfAccounts.Category newCategory) {
        this.classification = newClassification == null ? null : newClassification.name();
        this.category = newCategory == null ? null : newCategory.name();
    }

    /** The attributes this row sets, before inheritance is applied. */
    public ChartOfAccounts.Attributes attributes() {
        return new ChartOfAccounts.Attributes(
                ChartOfAccounts.Classification.fromStored(classification),
                ChartOfAccounts.Category.fromStored(category));
    }

    public boolean hasChildren() {
        return hasChildren;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /**
     * Called when a child is added. The database refuses this if the account already carries
     * postings; {@code ChartOfAccountsService} checks first so that the refusal can be read.
     */
    public void markAsParent() {
        this.hasChildren = true;
    }

    public void retire(OffsetDateTime at) {
        this.retiredAt = at;
    }

    /** Renaming is safe. The id, the type and the tree position are not touched. */
    public void rename(String newNameKo, String newNameEn) {
        this.nameKo = newNameKo;
        this.nameEn = newNameEn;
    }

    /** The domain object, with the flags that decide whether it can be posted to. */
    public Account toDomain() {
        Account account = new Account(id, parentId, nameKo, nameEn, currencyCode, contra);
        if (hasChildren) {
            account.markAsParent();
        }
        if (isRetired()) {
            account.retire();
        }
        return account;
    }

    /** The composite key: a chart of accounts belongs to one book, and codes repeat across books. */
    public static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private String bookId;
        private String id;

        public Key() {
        }

        public Key(String bookId, String id) {
            this.bookId = bookId;
            this.id = id;
        }

        public String bookId() {
            return bookId;
        }

        public String id() {
            return id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key other = (Key) obj;
            return (bookId == null ? other.bookId == null : bookId.equals(other.bookId))
                    && (id == null ? other.id == null : id.equals(other.id));
        }

        @Override
        public int hashCode() {
            int result = bookId == null ? 0 : bookId.hashCode();
            return result * 31 + (id == null ? 0 : id.hashCode());
        }

        @Override
        public String toString() {
            return bookId + "/" + id;
        }
    }
}
