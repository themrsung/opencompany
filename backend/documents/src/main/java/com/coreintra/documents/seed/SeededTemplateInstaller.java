package com.coreintra.documents.seed;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.entity.SeededTemplateInstallEntity;
import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.internal.adapter.MdvAdapter;
import com.coreintra.documents.ooxml.DocxWriter;
import com.coreintra.documents.repository.DocumentTemplateRepository;
import com.coreintra.documents.repository.SeededTemplateInstallRepository;
import com.coreintra.documents.service.TemplateBody;
import com.coreintra.documents.service.TemplateService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Installs the factory templates, idempotently.
 *
 * <h2>Why this is Java and not a migration</h2>
 *
 * <p>A seeded template is a DOCX package with content controls in it. A Flyway
 * migration cannot produce one: the bytes have to be generated, hashed, written
 * to the blob store, and cross-validated against the field manifest — which is
 * {@code TemplateService.publishVersion}'s job and involves opening the package
 * it just wrote. {@code V12__seeded_templates.sql} therefore creates only the
 * record of what was installed, and this runs against it.
 *
 * <h2>Idempotence, and what it must not do</h2>
 *
 * <p>Called on every boot. On the second boot it must do nothing at all — not
 * republish, not bump a version, not touch a blob. Republishing an unchanged
 * template would create a version per restart, and every version is a thing a
 * client can see and a document can be bound to.
 *
 * <p>It must also never overwrite a client's work. A client who edits the
 * seeded 휴가신청서 has published version 2 of it; raising the catalogue revision
 * publishes version 3 <em>alongside</em> that, and never rewrites version 1,
 * because version 1 is what already-submitted documents render from and
 * "restorable to factory state" means restorable to it.
 */
@Service
public class SeededTemplateInstaller {

    /** The account recorded as publisher. Null: nobody signed for a factory row. */
    private static final String FACTORY_ACCOUNT = null;

    private final TemplateService templates;
    private final DocumentTemplateRepository templateRepository;
    private final SeededTemplateInstallRepository installs;
    private final BinaryStore binaries;

    public SeededTemplateInstaller(TemplateService templates,
            DocumentTemplateRepository templateRepository,
            SeededTemplateInstallRepository installs,
            BinaryStore binaries) {
        this.templates = templates;
        this.templateRepository = templateRepository;
        this.installs = installs;
        this.binaries = binaries;
    }

    /**
     * Brings one company's factory templates up to the current catalogue.
     *
     * @param at the business instant to record as the publication moment
     * @return the codes actually installed or upgraded, empty when up to date
     */
    @Transactional
    public List<String> install(String companyId, BusinessInstant at) {
        List<String> changed = new ArrayList<String>();
        for (SeededTemplate seeded : SeededTemplateCatalogue.all()) {
            if (installOne(companyId, seeded, at)) {
                changed.add(seeded.code());
            }
        }
        return changed;
    }

    private boolean installOne(String companyId, SeededTemplate seeded, BusinessInstant at) {
        Optional<SeededTemplateInstallEntity> existing =
                installs.findByCompanyIdAndCode(companyId, seeded.code());
        if (existing.isPresent()
                && existing.get().catalogueRevision().intValue()
                        >= SeededTemplateCatalogue.REVISION) {
            return false;
        }

        DocumentTemplateEntity template;
        if (existing.isPresent()) {
            template = templateRepository.findById(existing.get().templateId())
                    .orElseThrow(() -> new IllegalStateException(
                            "the seed record for " + seeded.code() + " names template "
                                    + existing.get().templateId() + ", which does not exist. "
                                    + "Nothing deletes a template, so this is corruption rather "
                                    + "than a race."));
        } else {
            Optional<DocumentTemplateEntity> claimed =
                    templateRepository.findByCompanyIdAndCode(companyId, seeded.code());
            if (claimed.isPresent()) {
                // The client got there first with a template of their own under
                // our code. Theirs wins: installing over it would replace a
                // document somebody wrote with one we generated.
                return false;
            }
            template = templates.create(companyId, seeded.code(), seeded.documentType(),
                    seeded.nameKo(), seeded.nameEn(), FACTORY_ACCOUNT);
            template.setBuiltIn(true);
            templateRepository.save(template);
        }

        DocumentTemplateVersionEntity version = templates.publishVersion(
                template.id(), seeded.schema(), bodiesOf(seeded), FACTORY_ACCOUNT, at);

        OffsetDateTime now = OffsetDateTime.now();
        if (existing.isPresent()) {
            SeededTemplateInstallEntity record = existing.get();
            record.upgradedTo(SeededTemplateCatalogue.REVISION,
                    version.versionNo().intValue(), now);
            installs.save(record);
        } else {
            installs.save(new SeededTemplateInstallEntity(companyId, seeded.code(),
                    template.id(), SeededTemplateCatalogue.REVISION,
                    version.versionNo().intValue()));
        }
        return true;
    }

    /**
     * Serialises each body once, through the shared serialisers.
     *
     * <p>Deterministic by construction: {@link DocxWriter} fixes the zip
     * timestamps, so the same catalogue produces the same bytes and therefore
     * the same blob hash on every machine. A template whose hash moved on every
     * boot would store a fresh copy of itself each time.
     */
    private List<TemplateBody> bodiesOf(SeededTemplate seeded) {
        List<TemplateBody> bodies = new ArrayList<TemplateBody>();
        for (Map.Entry<String, InternalDoc> entry : seeded.bodies().entrySet()) {
            String locale = entry.getKey();
            boolean mdv = locale.endsWith("-x-mdv");
            byte[] content = mdv
                    ? new MdvAdapter().write(entry.getValue())
                    : new DocxWriter(binaries).write(entry.getValue());
            bodies.add(new TemplateBody(locale, mdv ? DocumentFormat.MDV : DocumentFormat.DOCX,
                    content, seeded.code().toLowerCase(java.util.Locale.ROOT) + "-" + locale
                            + (mdv ? ".mdv" : ".docx")));
        }
        return bodies;
    }
}
