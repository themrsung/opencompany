package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.repository.DocumentFieldValueRepository;
import com.coreintra.documents.repository.DocumentRepository;
import com.coreintra.documents.repository.DocumentTemplateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a document id into a domain object the evaluator can decide about, and
 * refuses on its behalf.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code EmployeeService} and its siblings take the caller and ask the
 * evaluator themselves, so their controllers only have to pass the principal
 * along. The documents services do not: {@code DocumentService},
 * {@code TemplateService} and {@code FontStoreService} take a bare
 * {@code companyId} and no caller, because they were written for the engine
 * beneath the API — the conversion worker, the approval snapshotter — where the
 * authority question was already settled by whoever queued the work.
 *
 * <p>Exposing them over HTTP puts a third party in the middle, so somebody has
 * to make the decision, and §3 says it is made on the domain object by the one
 * evaluator. This class is that translation and nothing else: it holds no
 * policy, never answers "allowed" without asking, and is the only place in the
 * document surface that reads a row for the purpose of deciding about it.
 *
 * <p>It reads repositories directly, which is why it is a service and not a
 * controller — the layer rule exists so that no route-level check is retrofitted
 * onto a query that already ran, and here the query <em>is</em> the fetch of the
 * object being decided about. The same argument, in the same words, is made by
 * {@code AccountSubjects} for the account surface. When the documents services
 * grow a {@code PermissionPrincipal} parameter this class should shrink to
 * nothing.
 *
 * <h2>What a document target carries, and what it cannot</h2>
 *
 * <p>A document row knows its company and the account that created it. It does
 * not know an org unit, and {@code createdByAccountId} is an account — possibly
 * a service account — rather than an employee. The target therefore carries the
 * company and the business date only, which means a grant must be
 * {@code COMPANY} scope or wider to reach a document. Deriving an org unit from
 * the author's position today would be a guess about a row that may be years
 * old, and a wrong guess would silently widen or narrow every check made here.
 * Reported rather than invented: if documents are to be unit-scoped, the row
 * needs an owning unit and the service needs to record it.
 */
@Service
public class DocumentAccess {

    private final DocumentRepository documents;
    private final DocumentTemplateRepository templates;
    private final DocumentFieldValueRepository fieldValues;
    private final PermissionEvaluator evaluator;

    public DocumentAccess(DocumentRepository documents, DocumentTemplateRepository templates,
            DocumentFieldValueRepository fieldValues, PermissionEvaluator evaluator) {
        this.documents = documents;
        this.templates = templates;
        this.fieldValues = fieldValues;
        this.evaluator = evaluator;
    }

    /**
     * The document, once the caller is allowed to do {@code key} to it.
     *
     * @return empty when no document has that id
     * @throws com.coreintra.core.permission.PermissionDeniedException naming the
     *         permission that was missing
     */
    @Transactional(readOnly = true)
    public Optional<DocumentEntity> document(PermissionPrincipal caller, String documentId,
            PermissionKey key, LocalDate on, String description) {
        Optional<DocumentEntity> found = documents.findById(documentId);
        if (!found.isPresent()) {
            return found;
        }
        DocumentEntity document = found.get();
        evaluator.check(caller, key, target(document.companyId(), on, description)).orThrow();
        return found;
    }

    /**
     * The template, once the caller is allowed to do {@code key} to it.
     *
     * @return empty when no template has that id
     */
    @Transactional(readOnly = true)
    public Optional<DocumentTemplateEntity> template(PermissionPrincipal caller, String templateId,
            PermissionKey key, LocalDate on, String description) {
        Optional<DocumentTemplateEntity> found = templates.findById(templateId);
        if (!found.isPresent()) {
            return found;
        }
        evaluator.check(caller, key, target(found.get().companyId(), on, description)).orThrow();
        return found;
    }

    /** Checks a company-wide capability: creating, listing, installing a font. */
    @Transactional(readOnly = true)
    public void company(PermissionPrincipal caller, String companyId, PermissionKey key,
            LocalDate on, String description) {
        evaluator.check(caller, key, target(companyId, on, description)).orThrow();
    }

    /** True when this caller may read this document. Used where a refusal is a filter. */
    @Transactional(readOnly = true)
    public boolean mayRead(PermissionPrincipal caller, DocumentEntity document, LocalDate on) {
        return evaluator.check(caller, DocumentPermissions.DOCUMENT_READ,
                target(document.companyId(), on, "document " + document.id())).isAllowed();
    }

    /**
     * Live documents in a company, filtered row by row.
     *
     * <p>Filtered rather than refused as a whole, for the reason
     * {@code EmployeeService} gives about people: one unreadable row is not a
     * reason to answer nothing. A page can therefore come back shorter than the
     * limit, which is why the end of the collection is the null cursor and never
     * a short page.
     *
     * @param documentType optional; null lists every type
     */
    @Transactional(readOnly = true)
    public List<DocumentEntity> list(PermissionPrincipal caller, String companyId,
            String documentType, LocalDate on) {
        List<DocumentEntity> rows = Texts.isBlank(documentType)
                ? documents.findByCompanyIdAndRetiredAtIsNull(companyId)
                : documents.findByCompanyIdAndDocumentTypeAndRetiredAtIsNull(companyId,
                        documentType);
        return filter(caller, rows, on);
    }

    /** Documents drafted from one published template version. The impact list before a change. */
    @Transactional(readOnly = true)
    public List<DocumentEntity> draftedFrom(PermissionPrincipal caller, String templateId,
            int versionNo, LocalDate on) {
        return filter(caller,
                documents.findByTemplateIdAndTemplateVersionNo(templateId,
                        Integer.valueOf(versionNo)), on);
    }

    /**
     * Field values matching an exact text, with the documents the caller may not
     * read removed.
     *
     * <p>The typed finders exist so that a caller cannot accidentally compare
     * money as text; the filtering exists so that a search cannot be used to
     * learn the contents of a document the caller cannot open.
     */
    @Transactional(readOnly = true)
    public List<DocumentFieldValueEntity> findByText(PermissionPrincipal caller, String fieldId,
            String valueText, LocalDate on) {
        return readable(caller, fieldValues.findByFieldIdAndValueText(fieldId, valueText), on);
    }

    /** Field values whose money is at least {@code minimum}. A comparison, not a guess. */
    @Transactional(readOnly = true)
    public List<DocumentFieldValueEntity> findByAmountAtLeast(PermissionPrincipal caller,
            String fieldId, BigDecimal minimum, LocalDate on) {
        return readable(caller,
                fieldValues.findByFieldIdAndValueAmountGreaterThanEqual(fieldId, minimum), on);
    }

    /** Field values pointing at one employee or org unit. */
    @Transactional(readOnly = true)
    public List<DocumentFieldValueEntity> findByReference(PermissionPrincipal caller,
            String fieldId, String refId, LocalDate on) {
        return readable(caller, fieldValues.findByFieldIdAndValueRefId(fieldId, refId), on);
    }

    private List<DocumentEntity> filter(PermissionPrincipal caller, List<DocumentEntity> rows,
            LocalDate on) {
        List<DocumentEntity> visible = new ArrayList<DocumentEntity>();
        for (DocumentEntity row : rows) {
            if (mayRead(caller, row, on)) {
                visible.add(row);
            }
        }
        return Immutables.copyOf(visible);
    }

    private List<DocumentFieldValueEntity> readable(PermissionPrincipal caller,
            List<DocumentFieldValueEntity> rows, LocalDate on) {
        List<DocumentFieldValueEntity> visible = new ArrayList<DocumentFieldValueEntity>();
        for (DocumentFieldValueEntity row : rows) {
            Optional<DocumentEntity> document = documents.findById(row.documentId());
            if (document.isPresent() && mayRead(caller, document.get(), on)) {
                visible.add(row);
            }
        }
        return Immutables.copyOf(visible);
    }

    private static PermissionTarget target(String companyId, LocalDate on, String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(on)
                .description(description)
                .build();
    }
}
