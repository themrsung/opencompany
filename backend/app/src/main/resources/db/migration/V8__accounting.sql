-- 회계 (double-entry accounting).
--
-- The engine already exists in backend/accounting/src/main/java and its
-- invariants are settled there: debits equal credits exactly or the entry does
-- not exist, nothing rounds on write, nothing is ever hard-deleted. This schema
-- stores that engine; it does not reinterpret it. Where a rule can be expressed
-- as a constraint it is one, because the application is only the first writer.
-- An import, a support session and a later MCP client all reach these tables,
-- and none of them will have read the Java.
--
-- The module is off-switchable (coreintra.accounting.enabled=false). The tables
-- are created either way. A schema that appeared and disappeared with a flag
-- could not be migrated forward, and an installation that turns accounting on
-- two years in must still find its history where the migration left it. The
-- flag removes beans, endpoints and UI. It never removes data.

-- A set of books. One company may keep several (statutory, management, a
-- disposal ledger) and they never mix: every account, entry and posting below
-- belongs to exactly one book, and the balance invariant is per book.
CREATE TABLE book (
    id                  VARCHAR(36)  PRIMARY KEY,
    company_id          VARCHAR(36)  NOT NULL REFERENCES company (id),
    name                VARCHAR(200) NOT NULL,
    -- Deliberately not a foreign key. Currencies are scoped to a book (below),
    -- so the book row has to exist before its own base currency can, and a
    -- circular reference between two tables that are written in one transaction
    -- is worse than this comment. BookService writes the base currency row in
    -- the same transaction as the book, which is where the pairing is kept.
    base_currency_code  VARCHAR(12)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at          TIMESTAMPTZ,
    CONSTRAINT book_name_unique_per_company UNIQUE (company_id, name)
);
CREATE INDEX book_company_idx ON book (company_id);

-- Client-definable units of account. A "currency" here may be a commodity, a
-- share count or carbon credits, so there is nothing money-specific in this
-- table: no minor-unit maths, no rate feed, no ISO membership test.
CREATE TABLE accounting_currency (
    id                VARCHAR(36)  PRIMARY KEY,
    book_id           VARCHAR(36)  NOT NULL REFERENCES book (id),
    code              VARCHAR(12)  NOT NULL,
    name_ko           VARCHAR(200) NOT NULL,
    name_en           VARCHAR(200) NOT NULL,
    symbol            VARCHAR(16),
    -- PRESENTATION ONLY. This is how many decimals a screen shows; it never
    -- limits, rounds or rescales anything stored. KRW displays 0 decimals and
    -- an allocation of 1,000,000 across three ways still stores
    -- 333333.33333333333333 in journal_posting.amount, in full, forever.
    -- If you ever find yourself reaching for this column while writing an
    -- amount, stop: rounding on write destroys information that no later report
    -- can recover, and that is the mistake this whole module is shaped to
    -- prevent (ADR 0004). It belongs in the read path and nowhere else.
    display_decimals  INTEGER      NOT NULL DEFAULT 0
        CONSTRAINT accounting_currency_display_decimals_sane CHECK (display_decimals BETWEEN 0 AND 12),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at        TIMESTAMPTZ,
    CONSTRAINT accounting_currency_unique_per_book UNIQUE (book_id, code)
);

-- 거래처 - the sub-ledger dimension. Carried on postings, so receivables and
-- payables age by counterparty without a second ledger to reconcile against.
CREATE TABLE accounting_client (
    id          VARCHAR(36)  PRIMARY KEY,
    book_id     VARCHAR(36)  NOT NULL REFERENCES book (id),
    name        VARCHAR(200) NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at  TIMESTAMPTZ,
    CONSTRAINT accounting_client_name_unique_per_book UNIQUE (book_id, name)
);

-- The chart of accounts: a tree, one per book.
CREATE TABLE account (
    book_id       VARCHAR(36)  NOT NULL REFERENCES book (id),
    -- The id is the account code the accountant chose ('1100'), not a UUID, and
    -- that is load-bearing: the type is read from its first character, here and
    -- in AccountType.fromAccountId. Codes repeat across books, so the key is
    -- (book_id, id).
    id            VARCHAR(36)  NOT NULL,
    parent_id     VARCHAR(36),
    type          VARCHAR(12)  NOT NULL
        CONSTRAINT account_type_known CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    name_ko       VARCHAR(200) NOT NULL,
    name_en       VARCHAR(200),
    -- NULL means the book's base currency. A named currency here does not make
    -- the account foreign: postings still balance in base (see journal_posting).
    currency_code VARCHAR(12),
    -- Accumulated depreciation under its asset, allowance under its receivable:
    -- sits inside its parent's section carrying the opposite normal balance,
    -- so it nets against the parent instead of inflating the section.
    contra        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Attributes resolve by nearest ancestor: NULL means "whatever my nearest
    -- ancestor says", never "none". A chart has hundreds of accounts and a
    -- handful of decisions, and repeating one decision on forty children is
    -- forty chances for them to disagree - a disagreement that surfaces as a
    -- cash-flow statement nobody can reconcile. Set it once, high up.
    classification VARCHAR(16)
        CONSTRAINT account_classification_known CHECK (classification IS NULL OR classification IN (
            'OPERATING', 'INVESTING', 'FINANCING', 'TAX', 'FX', 'OTHER')),
    -- What the account holds. The sub-ledger views read this rather than
    -- guessing from the account's name: receivables ageing wants RECEIVABLE,
    -- prepaid schedules want PREPAID_EXPENSE.
    category      VARCHAR(24)
        CONSTRAINT account_category_known CHECK (category IS NULL OR category IN (
            'CASH', 'CASH_EQUIVALENT', 'RECEIVABLE', 'PREPAID_EXPENSE', 'OPERATING_ASSET',
            'INVESTMENT', 'CREDIT_CARD', 'UNPAID_EXPENSE', 'LINE_OF_CREDIT', 'AMORTIZING_LOAN',
            'PROVISION', 'GUARANTEE', 'OTHER')),
    has_children  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Retirement, never deletion. A deleted account would silently rewrite
    -- prior-period reports; a retired one keeps every historical figure and
    -- accepts no new postings.
    retired_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (book_id, id),
    -- The type is immutable because it is a function of the primary key. To
    -- change an account's type you would have to change its id, and the id is
    -- what every posting in journal_posting points at, so the change cannot be
    -- made quietly. Changing the type alone would silently reclassify history:
    -- last year's balance sheet would move a figure with no journal entry
    -- explaining it. Retire the account and open a new one instead.
    CONSTRAINT account_type_matches_id_prefix CHECK (
        (type = 'ASSET'     AND id LIKE '1%') OR
        (type = 'LIABILITY' AND id LIKE '2%') OR
        (type = 'EQUITY'    AND id LIKE '3%') OR
        (type = 'INCOME'    AND id LIKE '4%') OR
        (type = 'EXPENSE'   AND id LIKE '5%')
    ),
    CONSTRAINT account_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT account_parent_in_same_book FOREIGN KEY (book_id, parent_id) REFERENCES account (book_id, id),
    -- Default MATCH SIMPLE, so a NULL currency_code (meaning "the book's base")
    -- satisfies this without a second constraint saying so.
    CONSTRAINT account_currency_defined FOREIGN KEY (book_id, currency_code)
        REFERENCES accounting_currency (book_id, code),
    -- Exists so journal_posting can point at (book, account, has_children) and
    -- pin the third column to FALSE. See the posting table.
    CONSTRAINT account_leafness_referenceable UNIQUE (book_id, id, has_children)
);
CREATE INDEX account_parent_idx ON account (book_id, parent_id);

-- Many entries in one transaction, atomic, and first-class afterwards: a batch
-- is listable, fetchable and voidable as a unit.
CREATE TABLE accounting_batch (
    id                VARCHAR(36)  PRIMARY KEY,
    book_id           VARCHAR(36)  NOT NULL REFERENCES book (id),
    kind              VARCHAR(20)  NOT NULL
        CONSTRAINT accounting_batch_kind_known CHECK (
            kind IN ('MANUAL', 'IMPORT', 'CLOSING', 'RECURRING', 'AMORTIZATION', 'FX_REVALUATION')),
    label             VARCHAR(200) NOT NULL,
    note              TEXT,
    -- Audit lineage for generated batches: the parameters a human approved,
    -- kept as text so they can be read years later without the generator that
    -- produced them. They are never re-executed. Re-running a schedule against
    -- today's chart of accounts would quietly produce a different past.
    -- Money inside this JSON is written as exact decimal strings, never as JSON
    -- numbers, for the reason given on Amount.
    generator_params  TEXT,
    created_by        VARCHAR(36)  REFERENCES user_account (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX accounting_batch_book_idx ON accounting_batch (book_id, kind);

COMMENT ON COLUMN accounting_batch.kind IS
    'CLOSING batches are excluded from income-statement reporting so that a closed '
    'year still reports what it earned. Including the closing entries would net income '
    'to zero and make the year look as though nothing happened.';

-- A journal entry. Two or more postings whose debits and credits are exactly
-- equal, or it does not exist.
CREATE TABLE journal_entry (
    id                     VARCHAR(36)  PRIMARY KEY,
    book_id                VARCHAR(36)  NOT NULL REFERENCES book (id),
    batch_id               VARCHAR(36)  REFERENCES accounting_batch (id),
    description            VARCHAR(500) NOT NULL,
    -- DRAFT and VOID are excluded from every report and stay in the journal.
    -- The journal is the record of what was done, including what was done
    -- wrongly; removing a row would hide the mistake as well as its effect.
    status                 VARCHAR(10)  NOT NULL
        CONSTRAINT journal_entry_status_known CHECK (status IN ('DRAFT', 'POSTED', 'VOID')),
    -- Business time, three columns (ADR 0002). Which business day an entry
    -- falls in decides which period reports it, so the date is the fact and
    -- the offset says how far into that day it happened.
    posted_business_date   DATE         NOT NULL,
    posted_offset_seconds  INTEGER      NOT NULL,
    posted_absolute_ts     TIMESTAMP GENERATED ALWAYS AS
                               (posted_business_date + make_interval(secs => posted_offset_seconds)) STORED,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_posted_offset_in_window CHECK (business_offset_is_valid(posted_offset_seconds)),
    -- Lets journal_posting carry book_id and still be held to its entry's book.
    CONSTRAINT journal_entry_id_book UNIQUE (id, book_id)
);
CREATE INDEX journal_entry_ordering_idx ON journal_entry (book_id, posted_business_date, posted_offset_seconds);
CREATE INDEX journal_entry_batch_idx ON journal_entry (batch_id);
CREATE INDEX journal_entry_reportable_idx ON journal_entry (book_id, posted_business_date)
    WHERE status = 'POSTED';

COMMENT ON TABLE journal_entry IS
    'Debits equal credits exactly or the entry does not exist. The sum spans rows, so it is a '
    'deferred constraint trigger (below journal_posting) rather than a CHECK. Three writers '
    'prove it: Entry.post() before anything is written, the loader again on every read, and the '
    'database at COMMIT for anyone who used neither. There is no delete - a correction is a '
    'numbered revision, and a wrong transaction is voided.';

-- One side of an entry: an account, an amount, and - for foreign currency -
-- both the account's amount and its base-currency equivalent.
CREATE TABLE journal_posting (
    id                    VARCHAR(36) PRIMARY KEY,
    entry_id              VARCHAR(36) NOT NULL,
    book_id               VARCHAR(36) NOT NULL,
    position              INTEGER     NOT NULL,
    account_id            VARCHAR(36) NOT NULL,
    -- Copied from the account so the foreign key below can pin it to FALSE.
    -- Written by the DEFAULT, never by the application.
    account_has_children  BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Positive is a debit, negative is a credit. One signed number rather than
    -- an amount plus a side flag, because a flag and a sign can disagree and
    -- then nothing downstream knows which to believe.
    amount                NUMERIC     NOT NULL,
    -- NULL means the posting is already in the book's base currency.
    currency_code         VARCHAR(12),
    -- What balances. Always in the book's base currency.
    base_amount           NUMERIC     NOT NULL,
    -- Supplied by the caller and recorded. The system never looks a rate up:
    -- a fetched rate is one nobody agreed to, and it changes what the books say
    -- depending on when the job ran.
    rate                  NUMERIC,
    client_id             VARCHAR(36) REFERENCES accounting_client (id),
    memo                  VARCHAR(500),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT journal_posting_unique_position UNIQUE (entry_id, position),
    CONSTRAINT journal_posting_entry_fk FOREIGN KEY (entry_id, book_id) REFERENCES journal_entry (id, book_id),
    -- Only leaves are postable, structurally. The posting stores the account's
    -- leafness and the foreign key forces the two to agree, while the CHECK
    -- pins the stored copy to FALSE. Giving an account a child therefore does
    -- two things at once: new postings to it are rejected, and the flip of
    -- has_children is itself refused if postings already reference the account.
    -- That refusal is the point. A parent that still holds postings makes its
    -- own subtotal wrong - the total of the children no longer equals the
    -- parent - and no report can tell you it happened. Move the postings to a
    -- child first, then add the child.
    CONSTRAINT journal_posting_account_is_a_leaf CHECK (account_has_children = FALSE),
    CONSTRAINT journal_posting_account_fk FOREIGN KEY (book_id, account_id, account_has_children)
        REFERENCES account (book_id, id, has_children),
    -- A zero posting contributes nothing and hides intent: whoever wrote it
    -- meant something, and the entry should say what.
    CONSTRAINT journal_posting_not_zero CHECK (amount <> 0),
    -- An amount denominated in a unit this book never defined is a figure with
    -- no meaning. NULL still means "the book's base currency".
    CONSTRAINT journal_posting_currency_defined FOREIGN KEY (book_id, currency_code)
        REFERENCES accounting_currency (book_id, code),
    -- A foreign-currency posting needs a rate and a base amount; a domestic one
    -- must not pretend to have either.
    CONSTRAINT journal_posting_fx_is_complete CHECK (
        (currency_code IS NULL AND rate IS NULL)
        OR (currency_code IS NOT NULL AND rate IS NOT NULL AND base_amount <> 0)),
    -- The amount and its base equivalent are the same side of the entry. A
    -- conversion that flips the sign is a debit becoming a credit.
    CONSTRAINT journal_posting_sign_agrees CHECK (sign(amount) = sign(base_amount))
);
CREATE INDEX journal_posting_entry_idx ON journal_posting (entry_id);
CREATE INDEX journal_posting_account_idx ON journal_posting (book_id, account_id);
CREATE INDEX journal_posting_client_idx ON journal_posting (client_id) WHERE client_id IS NOT NULL;

-- The balance invariant, at the last line of defence.
--
-- Entry.post() refuses an unbalanced entry and the loader re-proves it on every
-- read, so this fires for writers that never went through either: an import, a
-- support session, a client module, a future MCP tool. It is the same reasoning
-- as the 72-hour window in V1 - the rule belongs where nobody can be outside it.
--
-- DEFERRED because postings arrive one row at a time and an entry is unbalanced
-- in the middle of being written by definition. Checking per statement would
-- reject every legitimate write; checking at COMMIT asks the only question that
-- matters, which is whether what was written balances.
--
-- The cost is one small indexed aggregate per changed posting at commit. A ten
-- thousand line import pays it ten thousand times, and that is the right trade:
-- an import is exactly the writer this exists to catch.
--
-- Note the consequence for hand-written SQL: inserting an entry outside a
-- transaction fails, because at the end of that statement the entry has no
-- postings. Wrap it in a transaction. That pressure is intended.
CREATE FUNCTION journal_entry_balance_check()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    target     VARCHAR(36);
    line_count INTEGER;
    difference NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entry' THEN
        target := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        target := OLD.entry_id;
    ELSE
        target := NEW.entry_id;
    END IF;

    SELECT count(*), coalesce(sum(base_amount), 0)
      INTO line_count, difference
      FROM journal_posting
     WHERE entry_id = target;

    IF line_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s). An entry needs at least two: a '
            'single-sided entry cannot balance.', target, line_count;
    END IF;

    -- Numeric comparison, so 1000.00 and 1000 are the same figure. Differing
    -- scale is not an imbalance; a rounding gap is, and nothing here rounds one
    -- away to make the entry fit.
    IF difference <> 0 THEN
        RAISE EXCEPTION 'journal entry % is out of balance by % in the book base currency. '
            'Debits and credits must be exactly equal.', target, difference;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balances
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balance_check();

CREATE CONSTRAINT TRIGGER journal_posting_keeps_its_entry_balanced
    AFTER INSERT OR UPDATE OR DELETE ON journal_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balance_check();

-- A numbered correction with its reason and the state before it.
CREATE TABLE journal_entry_revision (
    id                     VARCHAR(36) PRIMARY KEY,
    entry_id               VARCHAR(36) NOT NULL REFERENCES journal_entry (id),
    number                 INTEGER     NOT NULL CHECK (number >= 1),
    kind                   VARCHAR(10) NOT NULL
        CONSTRAINT journal_entry_revision_kind_known CHECK (kind IN ('UPDATE', 'VOID')),
    -- Mandatory, and not blank. An unexplained reversal is indistinguishable
    -- from a mistake.
    reason                 TEXT        NOT NULL
        CONSTRAINT journal_entry_revision_reason_present CHECK (length(btrim(reason)) > 0),
    actor_account_id       VARCHAR(36) NOT NULL REFERENCES user_account (id),
    recorded_business_date  DATE       NOT NULL,
    recorded_offset_seconds INTEGER    NOT NULL,
    recorded_absolute_ts    TIMESTAMP GENERATED ALWAYS AS
                               (recorded_business_date + make_interval(secs => recorded_offset_seconds)) STORED,
    -- What the entry looked like before this revision. Text, and deliberately
    -- not a foreign key into anything: it must still read correctly after the
    -- accounts it names have been retired or renamed.
    pre_state_snapshot     TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_revision_numbered_once UNIQUE (entry_id, number),
    CONSTRAINT journal_entry_revision_offset_in_window CHECK (business_offset_is_valid(recorded_offset_seconds))
);
CREATE INDEX journal_entry_revision_trail_idx
    ON journal_entry_revision (entry_id, recorded_business_date, recorded_offset_seconds);

COMMENT ON TABLE journal_entry_revision IS
    'Append-only. No UPDATE, no DELETE: this is the record of what was corrected, by whom, '
    'when in business time, and what the entry said beforehand. A correcting entry is '
    'posted alongside rather than replacing, so the journal shows both what was done and '
    'what was done about it.';

-- Backfill for books that already exist. A fresh installation has no company
-- and therefore no book, so this inserts nothing; BookService seeds the same
-- two currencies when it opens a book. Both are editable and neither is
-- privileged - they are a usable starting point, not a definition of money.
INSERT INTO accounting_currency (id, book_id, code, name_ko, name_en, symbol, display_decimals)
SELECT md5('accounting-currency:' || b.id || ':' || c.code),
       b.id, c.code, c.name_ko, c.name_en, c.symbol, c.display_decimals
FROM book b
CROSS JOIN (VALUES
    ('KRW', '대한민국 원', 'South Korean won', '₩', 0),
    ('USD', '미국 달러',   'US dollar',        '$', 2)
) AS c(code, name_ko, name_en, symbol, display_decimals)
ON CONFLICT (book_id, code) DO NOTHING;
