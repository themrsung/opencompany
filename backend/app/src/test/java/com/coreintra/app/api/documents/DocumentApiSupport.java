package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.CompanyRepository;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionGrantRepository;
import com.coreintra.core.permission.PermissionGrantRow;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.ooxml.DocxWriter;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeding and fixtures shared by the document API tests.
 *
 * <h2>Who the caller is</h2>
 *
 * <p>A service-account principal, as an API key resolves to — a key is a
 * non-interactive credential and is audited as one, and it carries no employee.
 * That is why the grants here are attached to the account with {@code ALL}
 * scope: a {@code COMPANY}-scoped grant is reached through a position, and
 * positions belong to a person. See {@link #principal} for why it is not an
 * issued key.
 *
 * <h2>Why the docx is generated rather than checked in</h2>
 *
 * <p>{@link DocxWriter} writes the content controls the field manifest is
 * validated against, so a fixture built through it is a real package with real
 * {@code w:sdt} tags and cannot drift from the writer the way a committed binary
 * would.
 */
public final class DocumentApiSupport {

    public static final String APPLICANT = "applicantName";
    public static final String DEPARTMENT = "department";
    public static final String START_DATE = "leaveStartDate";
    public static final String DAYS = "leaveDays";
    public static final String AMOUNT = "amount";

    private DocumentApiSupport() {
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    /** A unique template code that fits the column, which is 40 characters. */
    public static String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String seedCompany(CompanyRepository companies) {
        String companyId = id();
        companies.save(new Company(companyId, "acme-" + companyId.substring(0, 8), "에이스전자",
                Company.CompanyKind.HEAD_OFFICE));
        return companyId;
    }

    public static String seedAccount(UserAccountRepository accounts, String username) {
        String accountId = id();
        accounts.save(new UserAccount(accountId, username + "-" + accountId.substring(0, 8),
                "테스트 계정", UserAccount.AccountKind.SERVICE_ACCOUNT));
        return accountId;
    }

    /** Grants one concrete key to one account, installation-wide. */
    public static void grant(PermissionGrantRepository grants, String accountId, PermissionKey key) {
        grants.save(new PermissionGrantRow(id(), GrantSource.USER_ACCOUNT, accountId, key,
                PermissionScope.ALL, true));
    }

    /**
     * The caller a request runs as.
     *
     * <p>These tests were written against a real scoped API key and a real
     * {@code Authorization} header, which is the honest way to exercise §10's
     * promise that a key can do what a browser can. That path is currently
     * unusable: {@code ApiKeyService} mints its key prefix with
     * {@code SecretHasher.randomToken(6)} and {@code randomToken} refuses
     * anything under 16 bytes, so <em>every</em> call to
     * {@code ApiKeyService.issue} throws. It is reported rather than worked
     * around in the auth module, which is not ours to edit.
     *
     * <p>So the caller is placed into the request-scoped holder instead. The
     * filter is still in the chain and still runs — it finds no credential,
     * declines to overwrite, and clears afterwards — so what is skipped is only
     * the credential-to-principal step, and everything these tests assert about
     * permissions is decided by the same evaluator on the same principal. Swap
     * this method back to an issued key the day the prefix length is fixed.
     */
    public static PermissionPrincipal principal(String accountId) {
        return PermissionPrincipal.serviceAccount(accountId, "테스트 서비스 계정",
                Immutables.<String>setOf());
    }

    /**
     * Removes everything a test put into the document tables for one company.
     *
     * <h2>Why a test cleans up after itself here</h2>
     *
     * <p>The database is shared by every integration test in the run, and the
     * other suites tidy their own areas with {@code delete from user_account} and
     * {@code delete from company}. A document left behind references both, so it
     * would turn somebody else's cleanup into a foreign-key failure in a test
     * that has nothing to do with documents — the worst kind of flake, because
     * the failing test is not the one at fault.
     *
     * <p>Scoped to the company rather than truncating the tables: a seeded
     * template or a demo document belongs to somebody else's fixture, and
     * emptying the table would break them in exactly the way this exists to
     * prevent.
     */
    public static void cleanUp(DataSource dataSource, String companyId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String documentsOfCompany = "(select id from document where company_id = ?)";
        String templatesOfCompany = "(select id from document_template where company_id = ?)";

        // Children before parents, or the deletes fail on the way down.
        jdbc.update("delete from signature_impression_use where document_id in "
                + documentsOfCompany, companyId);
        jdbc.update("delete from approval_snapshot where document_id in "
                + documentsOfCompany, companyId);
        jdbc.update("delete from document_field_value where document_id in "
                + documentsOfCompany, companyId);
        jdbc.update("delete from document_render where company_id = ?", companyId);
        jdbc.update("delete from conversion_job where company_id = ?", companyId);
        jdbc.update("delete from document_version where document_id in "
                + documentsOfCompany, companyId);
        jdbc.update("delete from document where company_id = ?", companyId);
        jdbc.update("delete from document_template_body where template_id in "
                + templatesOfCompany, companyId);
        jdbc.update("delete from document_template_version where template_id in "
                + templatesOfCompany, companyId);
        jdbc.update("delete from document_template where company_id = ?", companyId);
        jdbc.update("delete from font_substitution where company_id = ?", companyId);
        jdbc.update("delete from font_script_coverage where font_id in "
                + "(select id from font where company_id = ?)", companyId);
        jdbc.update("delete from font where company_id = ?", companyId);
    }

    /** The manifest that matches {@link #leaveRequestDocx}. */
    public static DocumentFieldSchema leaveRequestSchema() {
        return new DocumentFieldSchema(leaveRequestFields());
    }

    /** The same manifest with one extra field the document does not carry. */
    public static DocumentFieldSchema schemaWithUnmatchedField(String tag) {
        List<FieldDefinition> fields = leaveRequestFields();
        fields.add(FieldDefinition.of(tag, FieldType.TEXT, "없는 항목", "Absent field"));
        return new DocumentFieldSchema(fields);
    }

    public static List<FieldDefinition> leaveRequestFields() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required(APPLICANT, FieldType.TEXT, "신청자", "Applicant"));
        fields.add(FieldDefinition.of(DEPARTMENT, FieldType.TEXT, "부서", "Department"));
        fields.add(FieldDefinition.required(START_DATE, FieldType.DATE, "시작일", "Start date"));
        fields.add(FieldDefinition.of(DAYS, FieldType.NUMBER, "일수", "Days"));
        fields.add(FieldDefinition.of(AMOUNT, FieldType.MONEY, "금액", "Amount"));
        return fields;
    }

    /** The field manifest as the publish endpoint wants it, as JSON. */
    public static String schemaJson() {
        StringBuilder json = new StringBuilder("{\"fields\":[");
        List<FieldDefinition> fields = leaveRequestFields();
        for (int i = 0; i < fields.size(); i++) {
            FieldDefinition field = fields.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"tag\":\"").append(field.tag())
                .append("\",\"type\":\"").append(field.type().name())
                .append("\",\"labelKo\":\"").append(field.labelKo())
                .append("\",\"labelEn\":\"").append(field.labelEn())
                .append("\",\"required\":").append(field.isRequired())
                .append('}');
        }
        return json.append("]}").toString();
    }

    /** The same JSON with one field the document has no control for. */
    public static String schemaJsonWithUnmatchedField(String tag) {
        String base = schemaJson();
        return base.substring(0, base.length() - 2)
                + ",{\"tag\":\"" + tag + "\",\"type\":\"TEXT\",\"labelKo\":\"없는 항목\","
                + "\"labelEn\":\"Absent field\",\"required\":false}]}";
    }

    /** A docx carrying one content control per manifest field, with these values in them. */
    public static byte[] leaveRequestDocx(Map<String, String> values) {
        List<InternalDoc.Block> blocks = new ArrayList<InternalDoc.Block>();
        blocks.add(InternalDoc.Paragraph.heading("휴가신청서", 1));
        for (FieldDefinition field : leaveRequestFields()) {
            String value = values.get(field.tag());
            blocks.add(new InternalDoc.FieldBlock(field.tag(), value == null ? "" : value));
        }
        return new DocxWriter().write(
                new InternalDoc(blocks, values, "docx", null));
    }

    /** The values a filled-in leave request carries. */
    public static Map<String, String> filledValues(String amount) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(APPLICANT, "김민준");
        values.put(DEPARTMENT, "회계팀");
        values.put(START_DATE, "2026-08-20");
        values.put(DAYS, "3");
        values.put(AMOUNT, amount);
        return values;
    }

    /** An mdv body. The only format §6.10 lets us diff line by line. */
    public static byte[] mdv(String text) {
        return text.getBytes(com.coreintra.compat.Texts.UTF_8);
    }

    public static Set<String> scopes(String... values) {
        return Immutables.setOfArray(values);
    }
}
