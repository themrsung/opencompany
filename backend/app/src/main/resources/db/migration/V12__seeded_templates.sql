-- Which factory templates a company has, and at which revision of the catalogue.
--
-- The seven seeded templates (§6.8) are DOCX packages, and a migration cannot
-- write docx bytes into a blob store. They are therefore built in Java by
-- SeededTemplateCatalogue and installed on first boot through TemplateService,
-- which is the only path that stores a body, hashes it, and cross-validates the
-- field manifest against the document. This table is the part of that which
-- belongs in the schema: the record of what was installed.
--
-- Why the revision is here and not derived:
--
--   "Has this company got the seeded 지출결의서?" can be answered from
--   document_template. "Has it got the CURRENT one?" cannot. Without a recorded
--   revision, an installation whose template is simply old is indistinguishable
--   from one the client edited on purpose — and overwriting either is wrong.
--   With it, a raised catalogue revision publishes a new VERSION of the same
--   template, so a document already submitted against version 1 keeps rendering
--   exactly as it did, and a client's own edits are later versions that the
--   installer never touches.
--
-- There is no ON DELETE anywhere here for the usual reason: a template that has
-- been drafted from must remain resolvable forever, so nothing in this schema
-- deletes, it retires.
CREATE TABLE document_template_seed (
    company_id           VARCHAR(36) NOT NULL REFERENCES company (id),
    -- The catalogue's stable handle, not the template's display name: a client
    -- who renames 휴가신청서 to 연차신청서 has not stopped it being the seeded one.
    code                 VARCHAR(40) NOT NULL,
    template_id          VARCHAR(36) NOT NULL REFERENCES document_template (id),
    catalogue_revision   INTEGER     NOT NULL,
    -- The version this revision was published as. This is the version
    -- "restore to factory state" restores to; it is not always 1, because the
    -- second revision of a factory template lands as version 2 or later.
    installed_version_no INTEGER     NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ,
    PRIMARY KEY (company_id, code),
    CONSTRAINT document_template_seed_revision_positive
        CHECK (catalogue_revision >= 1),
    CONSTRAINT document_template_seed_version_positive
        CHECK (installed_version_no >= 1),
    -- One template row per code per company, and the template it names must
    -- belong to that company. A cross-tenant reference here would let one
    -- company's "restore to factory" rewrite another's document.
    CONSTRAINT document_template_seed_template_unique UNIQUE (template_id)
);

CREATE INDEX document_template_seed_stale_idx
    ON document_template_seed (catalogue_revision);

COMMENT ON TABLE document_template_seed IS
    'Factory template installs. Written by SeededTemplateInstaller on boot, not by a migration: '
    'the bodies are DOCX packages that have to be built and hashed into the blob store.';
