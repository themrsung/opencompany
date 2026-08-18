-- 문서 (documents, templates, fonts, renders).
--
-- The bytes are not in this file. Document bodies, fonts, signature images and
-- rendered PDFs live in the BlobStore addressed by SHA-256; these tables are
-- the index over them and the record of what each byte string means. That split
-- is what makes "the same attachment uploaded by forty people costs one copy"
-- true, and it is what keeps a database backup small enough to take hourly
-- while the blob volume is backed up on its own schedule.

-- The content-addressed index. One row per distinct byte string ever stored.
--
-- The primary key IS the content hash, and three things follow from that.
-- Storing identical bytes twice yields one row and one file. A stored blob
-- cannot be modified in place, only superseded - so an approval trail's hash is
-- proof rather than a promise. And "is this the document that was approved?" is
-- a string comparison, not a diff.
CREATE TABLE blob (
    sha256            VARCHAR(64)  PRIMARY KEY,
    size_bytes        BIGINT       NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    -- What the uploader called it. Never used as a path: the store addresses by
    -- hash, and a filename arriving from a client is attacker-controlled.
    original_filename VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- How deletion works here, given "no hard delete".
    --
    -- This row is never deleted. A hash that appears in an approval trail, in a
    -- render's metadata or in a client's export must always resolve to
    -- something; resolving to nothing is indistinguishable from a corrupted
    -- audit trail. What CAN be reclaimed is the file on the volume, once no
    -- live row references the hash - see the blob_unreferenced view below,
    -- which is the reaper's whole input. Reaping stamps this column and leaves
    -- a tombstone that still answers "these bytes existed, they were this long,
    -- and they were reclaimed on this date".
    bytes_reaped_at   TIMESTAMPTZ,
    CONSTRAINT blob_sha256_is_lowercase_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT blob_size_not_negative CHECK (size_bytes >= 0)
);

-- 서식 (templates). A template is a family; a template_version is what a
-- document is actually bound to.
CREATE TABLE document_template (
    id                  VARCHAR(36)  PRIMARY KEY,
    company_id          VARCHAR(36)  NOT NULL REFERENCES company (id),
    code                VARCHAR(40)  NOT NULL,
    document_type       VARCHAR(100) NOT NULL,
    name_ko             VARCHAR(200) NOT NULL,
    name_en             VARCHAR(200),
    -- NULL until a version is published. A template with no published version
    -- cannot be drafted from, which is the difference between "being written"
    -- and "available".
    current_version_no  INTEGER,
    -- Seeded rows can be restored to factory state; client rows cannot. It
    -- means nothing else: a forked seeded template behaves like any other.
    built_in            BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_account_id VARCHAR(36) REFERENCES user_account (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at          TIMESTAMPTZ,
    CONSTRAINT document_template_code_unique UNIQUE (company_id, code)
);
CREATE INDEX document_template_type_idx
    ON document_template (company_id, document_type) WHERE retired_at IS NULL;

-- A published version. Immutable: editing a template creates the next version,
-- because a submitted document promises to render identically forever and can
-- only keep that promise if the version it names never moves.
CREATE TABLE document_template_version (
    template_id           VARCHAR(36) NOT NULL REFERENCES document_template (id),
    version_no            INTEGER     NOT NULL,
    -- The field manifest as published: tag, type, ko/en labels, required flag.
    -- Stored with the version rather than with the template because a field
    -- added in v4 must not appear in a document drafted from v3.
    field_schema          TEXT        NOT NULL,
    -- Whether the body carries a 결재란 (the approvalBlock content control),
    -- detected at publish time. Recorded rather than re-detected, for the same
    -- reason the field values are: "can this template be submitted for 결재?"
    -- must be answerable without unzipping the docx, and the approval module
    -- has no business opening one.
    has_approval_block    BOOLEAN     NOT NULL DEFAULT FALSE,
    -- What the previous version was, so the version-compare UI can walk back
    -- without assuming version_no - 1 exists after a restore.
    supersedes_version_no INTEGER,
    published_by_account_id VARCHAR(36) REFERENCES user_account (id),
    published_business_date DATE       NOT NULL,
    published_offset_seconds INTEGER   NOT NULL,
    CONSTRAINT template_version_published_offset_in_window
        CHECK (business_offset_is_valid(published_offset_seconds)),
    published_absolute_ts TIMESTAMP GENERATED ALWAYS AS
        (published_business_date + make_interval(secs => published_offset_seconds)) STORED,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, version_no),
    CONSTRAINT template_version_positive CHECK (version_no >= 1),
    CONSTRAINT template_version_supersedes_earlier
        CHECK (supersedes_version_no IS NULL OR supersedes_version_no < version_no)
);
CREATE INDEX document_template_version_published_idx
    ON document_template_version (published_business_date, published_offset_seconds);

-- The bodies. One version, one schema, one body per language.
--
-- The parallel Korean and English bodies are the same version deliberately: a
-- client who edits the Korean wording must not silently create a version whose
-- English body is a different document. They are published together or not at
-- all, and both are validated against the one schema above.
CREATE TABLE document_template_body (
    template_id VARCHAR(36) NOT NULL,
    version_no  INTEGER     NOT NULL,
    locale      VARCHAR(16) NOT NULL,
    blob_sha256 VARCHAR(64) NOT NULL REFERENCES blob (sha256),
    format      VARCHAR(8)  NOT NULL CHECK (format IN ('DOCX', 'HWPX', 'HWP', 'MDV')),
    PRIMARY KEY (template_id, version_no, locale),
    FOREIGN KEY (template_id, version_no)
        REFERENCES document_template_version (template_id, version_no)
);
CREATE INDEX document_template_body_blob_idx ON document_template_body (blob_sha256);

-- A document. The mutable head; every byte it has ever had is in
-- document_version.
CREATE TABLE document (
    id                    VARCHAR(36)  PRIMARY KEY,
    company_id            VARCHAR(36)  NOT NULL REFERENCES company (id),
    document_type         VARCHAR(100) NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    -- NULL for the moment between INSERT and the first version being written.
    -- Any other NULL here is a bug: a document with no version has no bytes.
    current_version_no    INTEGER,
    -- The template version this document was created from, pinned. Not the
    -- template's current version: a document approved against v3 renders
    -- against v3 for the rest of its life, whatever v7 says.
    template_id           VARCHAR(36),
    template_version_no   INTEGER,
    created_by_account_id VARCHAR(36)  NOT NULL REFERENCES user_account (id),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at            TIMESTAMPTZ,
    FOREIGN KEY (template_id, template_version_no)
        REFERENCES document_template_version (template_id, version_no),
    -- Half a template reference is worse than none: it names a family without
    -- naming what to render, and the composite FK above cannot catch it because
    -- SQL treats a partially-NULL foreign key as satisfied.
    CONSTRAINT document_template_reference_whole CHECK (
        (template_id IS NULL) = (template_version_no IS NULL))
);
CREATE INDEX document_company_type_idx
    ON document (company_id, document_type) WHERE retired_at IS NULL;
CREATE INDEX document_template_usage_idx
    ON document (template_id, template_version_no);

-- One version's bytes. Append-only: no UPDATE, no DELETE. A correction is the
-- next version naming this one as superseded, so the trail shows both the
-- mistake and the fix - the same rule the approval and leave ledgers follow.
CREATE TABLE document_version (
    document_id           VARCHAR(36) NOT NULL REFERENCES document (id),
    version_no            INTEGER     NOT NULL,
    blob_sha256           VARCHAR(64) NOT NULL REFERENCES blob (sha256),
    format                VARCHAR(8)  NOT NULL CHECK (format IN ('DOCX', 'HWPX', 'HWP', 'MDV')),
    -- 작성 시각: when the organisation says this draft was authored. Business
    -- time, because a document authored at 26:30 belongs to that business day
    -- and its approval ordering depends on it.
    authored_business_date  DATE      NOT NULL,
    authored_offset_seconds INTEGER   NOT NULL,
    CONSTRAINT document_version_authored_offset_in_window
        CHECK (business_offset_is_valid(authored_offset_seconds)),
    authored_absolute_ts  TIMESTAMP GENERATED ALWAYS AS
        (authored_business_date + make_interval(secs => authored_offset_seconds)) STORED,
    author_account_id     VARCHAR(36) NOT NULL REFERENCES user_account (id),
    supersedes_version_no INTEGER,
    -- UTC: what the machine observed. Never conflated with the above.
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version_no),
    CONSTRAINT document_version_positive CHECK (version_no >= 1),
    CONSTRAINT document_version_supersedes_earlier
        CHECK (supersedes_version_no IS NULL OR supersedes_version_no < version_no)
);
COMMENT ON TABLE document_version IS
    'Append-only. The immutable record of what the document was at each point. '
    'A submitted version is frozen by the approval module and its hash is the '
    'evidence; rewriting a row here would destroy that evidence silently.';
CREATE INDEX document_version_blob_idx ON document_version (blob_sha256);
CREATE INDEX document_version_authored_idx
    ON document_version (authored_business_date, authored_offset_seconds);

-- Extracted field values, so a document is queryable without opening it.
--
-- The docx remains the source of truth (§6.1): these rows are a projection,
-- rebuilt from the content controls every time a version is written. They exist
-- because "every expense over 5,000,000 last quarter" must not mean unzipping
-- forty thousand packages, and because a report cannot join on a value that
-- only exists inside a ZIP.
--
-- Typed columns rather than one text column: a MONEY field compared as text
-- sorts 9 above 10, and a DATE field compared as text is only accidentally
-- right. The CHECK below makes the column match the declared type, so a query
-- that reads value_amount can trust that every MONEY row populated it.
--
-- An empty optional field writes no row at all. A row whose value columns are
-- all NULL says nothing and is indistinguishable from a bug in the extractor.
CREATE TABLE document_field_value (
    document_id       VARCHAR(36)  NOT NULL,
    version_no        INTEGER      NOT NULL,
    -- The w:tag on the content control. The join between manifest and document.
    field_id          VARCHAR(100) NOT NULL,
    field_type        VARCHAR(24)  NOT NULL CHECK (field_type IN (
        'TEXT', 'MULTILINE_TEXT', 'NUMBER', 'MONEY', 'DATE',
        'BUSINESS_INSTANT', 'EMPLOYEE_REF', 'ORG_REF', 'FILE', 'TABLE')),
    -- TEXT, MULTILINE_TEXT, and the encoded rows of a TABLE. A table's cells
    -- are deliberately not queryable per-cell: that needs a fourth table and
    -- nobody has asked for it, and pretending a TSV is a relation would be
    -- worse than saying so here.
    value_text        TEXT,
    value_number      NUMERIC,
    -- Money is NUMERIC and unbounded with its currency beside it (ADR 0004).
    value_amount      NUMERIC,
    value_currency_code VARCHAR(12),
    value_date        DATE,
    -- A BUSINESS_INSTANT field: attendance and approval fields need to say
    -- 27:00, which a date picker cannot express and a timestamp cannot hold.
    value_business_date  DATE,
    value_offset_seconds INTEGER,
    CONSTRAINT document_field_value_offset_in_window
        CHECK (value_offset_seconds IS NULL OR business_offset_is_valid(value_offset_seconds)),
    value_absolute_ts TIMESTAMP GENERATED ALWAYS AS
        (value_business_date + make_interval(secs => value_offset_seconds)) STORED,
    -- EMPLOYEE_REF / ORG_REF. Not a foreign key: a field may name an employee
    -- of another company on a shared document, and an approved document must
    -- not become unreadable because someone was later removed.
    value_ref_id      VARCHAR(36),
    -- FILE: an attachment, addressed like everything else.
    value_blob_sha256 VARCHAR(64) REFERENCES blob (sha256),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version_no, field_id),
    FOREIGN KEY (document_id, version_no)
        REFERENCES document_version (document_id, version_no),
    CONSTRAINT document_field_value_matches_declared_type CHECK (
        CASE field_type
            WHEN 'TEXT'             THEN value_text IS NOT NULL
            WHEN 'MULTILINE_TEXT'   THEN value_text IS NOT NULL
            WHEN 'TABLE'            THEN value_text IS NOT NULL
            WHEN 'NUMBER'           THEN value_number IS NOT NULL
            WHEN 'MONEY'            THEN value_amount IS NOT NULL AND value_currency_code IS NOT NULL
            WHEN 'DATE'             THEN value_date IS NOT NULL
            WHEN 'BUSINESS_INSTANT' THEN value_business_date IS NOT NULL
                                         AND value_offset_seconds IS NOT NULL
            WHEN 'EMPLOYEE_REF'     THEN value_ref_id IS NOT NULL
            WHEN 'ORG_REF'          THEN value_ref_id IS NOT NULL
            WHEN 'FILE'             THEN value_blob_sha256 IS NOT NULL
            ELSE FALSE
        END)
);
-- "Which documents have field X = v" - the reporting access path.
--
-- Truncated because a btree tuple cannot hold an unbounded body and a 40kB
-- MULTILINE_TEXT field would fail the INSERT rather than the index. The first
-- 200 characters narrow the scan; the planner rechecks the full value.
CREATE INDEX document_field_value_text_idx
    ON document_field_value (field_id, left(value_text, 200));
CREATE INDEX document_field_value_amount_idx ON document_field_value (field_id, value_amount);
CREATE INDEX document_field_value_ref_idx ON document_field_value (field_id, value_ref_id);

-- 글꼴 (fonts). One font store, three consumers: the conversion worker's
-- fontconfig, the browser editor's webfont, and mdv's pdf.fonts. All three read
-- this table, so they cannot disagree about what is installed (§6.9).
CREATE TABLE font (
    id                  VARCHAR(36)  PRIMARY KEY,
    -- NULL means shipped with the product and available to every company. A
    -- client upload is always scoped to the client that accepted the licence.
    company_id          VARCHAR(36)  REFERENCES company (id),
    family              VARCHAR(200) NOT NULL,
    style               VARCHAR(100) NOT NULL DEFAULT 'Regular',
    file_format         VARCHAR(16)  NOT NULL,
    blob_sha256         VARCHAR(64)  NOT NULL REFERENCES blob (sha256),
    source              VARCHAR(20)  NOT NULL
        CHECK (source IN ('BUNDLED', 'CLIENT_UPLOADED', 'HOST_PROVIDED')),
    -- The font's own declared embedding permission, read from its OS/2 table.
    -- Reported, never enforced by us: honouring it is the client's obligation,
    -- and pretending otherwise would imply a check we do not do.
    embedding_permission VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (embedding_permission IN
            ('INSTALLABLE', 'RESTRICTED', 'PRINT_AND_PREVIEW', 'EDITABLE', 'UNKNOWN')),
    -- The raw OS/2 fsType bits, kept beside our reading of them. The manager UI
    -- shows this verbatim: if our parsing is coarser than the font's actual
    -- declaration, the client can still see what they are agreeing about, which
    -- is the whole reason the value is surfaced at all. NULL = unreadable.
    fs_type_raw         INTEGER,
    uploaded_by_account_id VARCHAR(36) REFERENCES user_account (id),
    uploaded_at         TIMESTAMPTZ,
    -- The exact words the uploader ticked, stored verbatim. This is the only
    -- evidence that anyone took responsibility for the licence, which is the
    -- entire point of asking.
    licence_acknowledgement_text TEXT,
    -- A disabled font stays installed and stops being offered. A timestamp
    -- rather than a flag because "when did this stop being used?" is the first
    -- question asked when a PDF from last month looks wrong. Removal is
    -- separate and warns with the count of renders that reference it first.
    disabled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at          TIMESTAMPTZ,
    -- A client upload with no recorded acknowledgement is an unlicensed font
    -- with nobody's name on it. The service refuses to build one; this refuses
    -- to store one, because the two paths fail differently and both matter.
    CONSTRAINT font_client_upload_is_acknowledged CHECK (
        source <> 'CLIENT_UPLOADED'
        OR (uploaded_by_account_id IS NOT NULL
            AND licence_acknowledgement_text IS NOT NULL
            AND length(btrim(licence_acknowledgement_text)) > 0)),
    -- NULLS NOT DISTINCT, because company_id IS NULL is a real scope here - the
    -- shipped fonts - and under the default rule a UNIQUE constraint containing
    -- a NULL never fires, which would let Pretendard Regular be installed
    -- globally twice and leave the resolver picking one of them.
    CONSTRAINT font_family_style_unique_per_scope
        UNIQUE NULLS NOT DISTINCT (company_id, family, style)
);
CREATE INDEX font_family_idx ON font (family) WHERE retired_at IS NULL;
CREATE INDEX font_blob_idx ON font (blob_sha256);

-- Which scripts a font actually covers, one ISO 15924 code per row.
--
-- A row per script rather than a delimited column because the question asked at
-- render time is "is anything installed that covers Deva?", and answering it
-- with LIKE '%Deva%' would also match a family called "Devanagari Placeholder".
CREATE TABLE font_script_coverage (
    font_id     VARCHAR(36) NOT NULL REFERENCES font (id) ON DELETE CASCADE,
    script_code VARCHAR(8)  NOT NULL,
    PRIMARY KEY (font_id, script_code)
);
CREATE INDEX font_script_coverage_script_idx ON font_script_coverage (script_code);

-- 대체 글꼴 (the substitution map): client-editable family -> fallback chain,
-- per script. A client running a CJK + Arabic + Devanagari document set maps
-- each script to a face that covers it, without asking us.
CREATE TABLE font_substitution (
    id              VARCHAR(36)  PRIMARY KEY,
    company_id      VARCHAR(36)  NOT NULL REFERENCES company (id),
    scope           VARCHAR(8)   NOT NULL CHECK (scope IN ('FAMILY', 'SCRIPT')),
    -- The requested family, or the ISO 15924 script code, depending on scope.
    scope_key       VARCHAR(200) NOT NULL,
    -- Chains are ordered and consulted in order: the first installed family
    -- that covers the script wins. Without the ordinal the chain is a set and
    -- "prefer Pretendard, then Noto" cannot be expressed.
    position        INTEGER      NOT NULL,
    fallback_family VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT font_substitution_position_unique UNIQUE (company_id, scope, scope_key, position),
    CONSTRAINT font_substitution_position_positive CHECK (position >= 0)
);
CREATE INDEX font_substitution_lookup_idx ON font_substitution (company_id, scope, scope_key);

-- 도장 / 서명 images, per employee (§6.3).
--
-- The bytes are never served to a browser. They are composited into a render
-- server-side and nowhere else, because an image URL that returns a person's
-- seal is a forgery kit with an access log. Enrolment and every use are
-- recorded; revocation is a stamp here, not a DELETE, so a document rendered
-- last year still explains which seal it carried.
CREATE TABLE signature_image (
    id                  VARCHAR(36) PRIMARY KEY,
    company_id          VARCHAR(36) NOT NULL REFERENCES company (id),
    employee_id         VARCHAR(36) NOT NULL REFERENCES employee (id),
    kind                VARCHAR(16) NOT NULL CHECK (kind IN ('SEAL', 'SIGNATURE')),
    blob_sha256         VARCHAR(64) NOT NULL REFERENCES blob (sha256),
    content_type        VARCHAR(64) NOT NULL,
    enrolled_by_account_id VARCHAR(36) NOT NULL REFERENCES user_account (id),
    enrolled_business_date  DATE      NOT NULL,
    enrolled_offset_seconds INTEGER   NOT NULL,
    CONSTRAINT signature_enrolled_offset_in_window
        CHECK (business_offset_is_valid(enrolled_offset_seconds)),
    enrolled_absolute_ts TIMESTAMP GENERATED ALWAYS AS
        (enrolled_business_date + make_interval(secs => enrolled_offset_seconds)) STORED,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    revoked_by_account_id VARCHAR(36) REFERENCES user_account (id),
    revoked_reason      TEXT,
    -- A revocation with no reason is a mystery in an audit six months later,
    -- and this is the table where mysteries matter most.
    CONSTRAINT signature_revocation_has_reason CHECK (
        revoked_at IS NULL
        OR (revoked_reason IS NOT NULL AND length(btrim(revoked_reason)) > 0
            AND revoked_by_account_id IS NOT NULL))
);
-- At most one live seal and one live signature per employee: two live seals
-- means a renderer choosing one, and nobody can say afterwards which it chose.
CREATE UNIQUE INDEX signature_image_live_unique_idx
    ON signature_image (employee_id, kind) WHERE revoked_at IS NULL;

-- Every time a seal's bytes were handed to the compositor, and for what.
-- Append-only. Without it, "was my seal used on this?" has no answer.
CREATE TABLE signature_impression_use (
    id                  VARCHAR(36) PRIMARY KEY,
    signature_image_id  VARCHAR(36) NOT NULL REFERENCES signature_image (id),
    document_id         VARCHAR(36) REFERENCES document (id),
    version_no          INTEGER,
    -- Who caused the render. Not who owns the seal: those differ exactly when
    -- this table is worth reading.
    requested_by_account_id VARCHAR(36) REFERENCES user_account (id),
    purpose             VARCHAR(40) NOT NULL,
    used_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE signature_impression_use IS
    'Append-only. The record of every occasion a signature image left the store.';
CREATE INDEX signature_impression_use_image_idx
    ON signature_impression_use (signature_image_id, used_at DESC);
CREATE INDEX signature_impression_use_document_idx
    ON signature_impression_use (document_id, version_no);

-- An archived render, with everything that determined how it came out (§6.4).
--
-- The lookup key is (document, version, format, config_fingerprint). The
-- fingerprint covers the renderer version, the template version, the resolved
-- font set, the substitutions and the locale - everything that could make the
-- same document produce different bytes. A hit here is returned in preference
-- to re-rendering, which is both the cache and the reproducibility contract:
-- if a re-render ever fails to match, the archived row is authoritative and
-- render_metadata explains what changed.
CREATE TABLE document_render (
    id                  VARCHAR(36)  PRIMARY KEY,
    company_id          VARCHAR(36)  NOT NULL REFERENCES company (id),
    document_id         VARCHAR(36)  NOT NULL,
    version_no          INTEGER      NOT NULL,
    format              VARCHAR(8)   NOT NULL
        CHECK (format IN ('PDF', 'DOCX', 'DOC', 'HWP', 'HWPX', 'HTML', 'MDV')),
    config_fingerprint  VARCHAR(64)  NOT NULL,
    output_blob_sha256  VARCHAR(64)  NOT NULL REFERENCES blob (sha256),
    -- Pinned, and recorded on every render. 'LibreOffice 24.2.7.2', not 'latest'.
    renderer_version    VARCHAR(200) NOT NULL,
    template_id         VARCHAR(36),
    template_version_no INTEGER,
    locale              VARCHAR(16),
    -- family -> content hash, one per line. Family to CONTENT hash, so "the
    -- same fonts" is checkable rather than assumed: two boxes can both have
    -- "Pretendard" installed and disagree about what it looks like.
    font_set            TEXT         NOT NULL DEFAULT '',
    -- Every substitution the resolver made, in words. Empty on a clean render.
    substitutions       TEXT         NOT NULL DEFAULT '',
    -- Format-specific extras: mdv's spec version, theme and buildTime go here.
    extra               TEXT         NOT NULL DEFAULT '',
    document_sha256     VARCHAR(64),
    output_sha256       VARCHAR(64)  NOT NULL,
    rendered_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (document_id, version_no)
        REFERENCES document_version (document_id, version_no),
    CONSTRAINT document_render_config_unique
        UNIQUE (document_id, version_no, format, config_fingerprint)
);
CREATE INDEX document_render_output_idx ON document_render (output_blob_sha256);

-- 결재 스냅샷. What was approved, at every step, provably.
--
-- Brief 6.3: at every approval step, snapshot the docx bytes, the rendered PDF,
-- the approval-block state and a SHA-256 of each. This table is that snapshot,
-- keyed by the approval action so the trail has no gaps: an action with no row
-- here is an approval nobody can reconstruct.
--
-- Why store the hashes when the blob key IS the hash: because the two answer
-- different questions. The blob reference says where the bytes are; the hash
-- column says what this row asserts they were. If a future migration, a restore
-- from an older backup or a bug ever repointed the reference, the columns would
-- disagree and the disagreement is the finding. Storing only one of them makes
-- that class of error undetectable, which for the approval trail is the whole
-- point of having it.
--
-- The approval-block state is the resolved 결재란 as rendered: 직급, name,
-- action, business-time date and whether a 도장 was composited, per approver.
-- Kept as text rather than rebuilt from the org chart, because the org chart
-- moves and the document does not.
CREATE TABLE approval_snapshot (
    -- The action is the key. One approval step, one snapshot, and a duplicate
    -- is a bug rather than a second opinion.
    approval_action_id  VARCHAR(36) PRIMARY KEY REFERENCES approval_action (id),
    document_id         VARCHAR(36) NOT NULL,
    version_no          INTEGER     NOT NULL,
    docx_blob_sha256    VARCHAR(64) NOT NULL REFERENCES blob (sha256),
    docx_sha256         VARCHAR(64) NOT NULL,
    -- NULL only where the conversion worker was down at the moment of approval.
    -- The docx is still authoritative and the PDF can be produced later, but the
    -- gap is recorded rather than filled in silently with a PDF made afterwards
    -- against a font set that may have changed.
    pdf_blob_sha256     VARCHAR(64) REFERENCES blob (sha256),
    pdf_sha256          VARCHAR(64),
    pdf_render_id       VARCHAR(36) REFERENCES document_render (id),
    approval_block_state TEXT       NOT NULL,
    approval_block_sha256 VARCHAR(64) NOT NULL,
    -- 도장 composited into this snapshot's PDF, if any. Names the enrolment, not
    -- the image: a revoked seal must still be identifiable on a document that
    -- legitimately carried it.
    signature_image_id  VARCHAR(36) REFERENCES signature_image (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (document_id, version_no)
        REFERENCES document_version (document_id, version_no),
    CONSTRAINT approval_snapshot_pdf_is_whole CHECK (
        (pdf_blob_sha256 IS NULL) = (pdf_sha256 IS NULL))
);
COMMENT ON TABLE approval_snapshot IS
    'Append-only. A document as approved must be reproducible forever, '
    'independent of any later template edit, font change or org restructure.';
CREATE INDEX approval_snapshot_document_idx
    ON approval_snapshot (document_id, version_no);

-- The conversion queue (§6.4). Conversion is slow and memory-hungry and must
-- never occupy a request thread, so it is a job with a lease.
--
-- Bounded: attempt_count against max_attempts, so a document that crashes the
-- worker is abandoned with an error a person can read rather than retried
-- until the end of time. Idempotent: idempotency_key is unique, so a user
-- pressing Export twice joins the existing job instead of starting a second
-- LibreOffice.
CREATE TABLE conversion_job (
    id                 VARCHAR(36)  PRIMARY KEY,
    company_id         VARCHAR(36)  NOT NULL REFERENCES company (id),
    -- Which worker takes it. LibreOffice and the Node mdv exporter are separate
    -- processes with separate failure modes, and a job that names neither would
    -- be picked up by whichever polled first.
    kind               VARCHAR(20)  NOT NULL
        CHECK (kind IN ('LIBREOFFICE', 'MDV')),
    document_id        VARCHAR(36)  NOT NULL,
    version_no         INTEGER      NOT NULL,
    -- The bytes to convert, named directly. The worker does not resolve a
    -- document to a version to a blob: three lookups it could get wrong, on a
    -- document that may have gained a version since the job was queued.
    source_blob_sha256 VARCHAR(64)  NOT NULL REFERENCES blob (sha256),
    target_format      VARCHAR(8)   NOT NULL
        CHECK (target_format IN ('PDF', 'DOCX', 'DOC', 'HWP', 'HWPX', 'HTML', 'MDV')),
    config_fingerprint VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(200) NOT NULL,
    -- ABANDONED is the fifth state the obvious four need: a job that has spent
    -- its attempts is not FAILED-and-retryable, and conflating them leaves the
    -- queue choosing between retrying forever and losing the retry entirely.
    state              VARCHAR(16)  NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    max_attempts       INTEGER      NOT NULL DEFAULT 3,
    -- Who holds the lease and until when. A worker that dies holds nothing once
    -- the lease expires; without the expiry a crash parks the job forever.
    lease_owner        VARCHAR(200),
    lease_expires_at   TIMESTAMPTZ,
    render_id          VARCHAR(36)  REFERENCES document_render (id),
    -- Split so the UI can say something useful in Korean. The code is ours and
    -- is translatable; the detail is the worker's own words and is not. A single
    -- free-text column forces the UI to show an English stack trace to a Korean
    -- user, or to show nothing.
    error_code         VARCHAR(60),
    error_detail       TEXT,
    requested_by_account_id VARCHAR(36) REFERENCES user_account (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    CONSTRAINT conversion_job_idempotency_unique UNIQUE (idempotency_key),
    CONSTRAINT conversion_job_attempts_bounded
        CHECK (max_attempts >= 1 AND attempt_count >= 0 AND attempt_count <= max_attempts),
    CONSTRAINT conversion_job_lease_is_whole CHECK (
        state <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    -- A job that succeeded without producing a render is a lie the export UI
    -- would then have to resolve by re-rendering, silently.
    CONSTRAINT conversion_job_success_has_render CHECK (
        state <> 'SUCCEEDED' OR render_id IS NOT NULL),
    CONSTRAINT conversion_job_failure_has_error CHECK (
        state NOT IN ('FAILED', 'ABANDONED')
        OR (error_code IS NOT NULL AND length(btrim(error_code)) > 0))
);
-- The worker's claim query: oldest queued job, or one whose lease has lapsed.
CREATE INDEX conversion_job_claimable_idx
    ON conversion_job (state, lease_expires_at, created_at);
CREATE INDEX conversion_job_document_idx ON conversion_job (document_id, version_no);

-- Every live reference to a blob, in one place.
--
-- The reaper reads blob_unreferenced and nothing else. Adding a table that
-- stores a hash without adding it here is the way to delete a client's approved
-- document by accident, so the view is the checklist: a new blob_sha256 column
-- anywhere means a new branch in this UNION.
CREATE VIEW blob_reference AS
    SELECT blob_sha256 AS sha256, 'document_version'::text AS referenced_by FROM document_version
    UNION ALL
    SELECT blob_sha256, 'document_template_body'::text FROM document_template_body
    UNION ALL
    SELECT blob_sha256, 'font'::text FROM font
    UNION ALL
    SELECT blob_sha256, 'signature_image'::text FROM signature_image
    UNION ALL
    SELECT output_blob_sha256, 'document_render'::text FROM document_render
    UNION ALL
    SELECT value_blob_sha256, 'document_field_value'::text FROM document_field_value
        WHERE value_blob_sha256 IS NOT NULL
    UNION ALL
    SELECT source_blob_sha256, 'conversion_job'::text FROM conversion_job
    UNION ALL
    SELECT docx_blob_sha256, 'approval_snapshot'::text FROM approval_snapshot
    UNION ALL
    SELECT pdf_blob_sha256, 'approval_snapshot'::text FROM approval_snapshot
        WHERE pdf_blob_sha256 IS NOT NULL;

-- Candidates for reaping: bytes on the volume that nothing points at any more.
-- Note what this is NOT: it is not a delete list for these rows. The row stays;
-- only the file is reclaimed, and bytes_reaped_at records that it was.
CREATE VIEW blob_unreferenced AS
    SELECT b.*
    FROM blob b
    WHERE b.bytes_reaped_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM blob_reference r WHERE r.sha256 = b.sha256);
