package com.coreintra.documents.seed;

import com.coreintra.compat.Immutables;
import com.coreintra.documents.internal.InternalDoc;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seven factory templates (§6.8).
 *
 * <h2>What "seeded" means here</h2>
 *
 * <p>These are a starting point, not a fixed part of the product. A client
 * forks them, renames the fields, adds their own, or ignores them entirely, and
 * nothing in the application reads a field id defined here by name — except the
 * two documented couplings the brief asks for: 휴가신청서 writes the leave ledger
 * on approval and 사직서 starts offboarding. Both are wired by document
 * <em>type</em>, so a client's own leave form works identically.
 *
 * <h2>Why the revision number matters</h2>
 *
 * <p>{@link #REVISION} is what the installer records. Publishing a corrected
 * 지출결의서 means raising it, which publishes version 2 for every company that
 * has revision 1 — leaving every document already submitted against version 1
 * rendering exactly as it did, because a submitted document names its version
 * and versions are immutable.
 *
 * <h2>The Korean copy</h2>
 *
 * <p>합쇼체 throughout, and written as a Korean product writer would write it
 * rather than translated from the English. The English body is a parallel
 * document, not a gloss: field <em>labels</em> are translatable, field
 * <em>data</em> is not (§6.8).
 */
public final class SeededTemplateCatalogue {

    /** Raise this when the factory content changes. See the class comment. */
    public static final int REVISION = 1;

    public static final String LEAVE_REQUEST = "LEAVE_REQUEST";
    public static final String EXPENSE_APPROVAL = "EXPENSE_APPROVAL";
    public static final String MEETING_MINUTES = "MEETING_MINUTES";
    public static final String GENERAL_AGENDA = "GENERAL_AGENDA";
    public static final String BOARD_AGENDA = "BOARD_AGENDA";
    public static final String SHAREHOLDER_AGENDA = "SHAREHOLDER_AGENDA";
    public static final String RESIGNATION = "RESIGNATION";

    /** The 결재 line every form carries, and every client immediately edits. */
    private static final String[] APPROVAL_ROLES_KO = {"담당", "팀장", "본부장", "대표이사"};
    private static final String[] APPROVAL_ROLES_EN = {"Staff", "Team lead", "Division head", "CEO"};

    private SeededTemplateCatalogue() {
    }

    public static List<SeededTemplate> all() {
        List<SeededTemplate> templates = new ArrayList<SeededTemplate>();
        templates.add(leaveRequest());
        templates.add(expenseApproval());
        templates.add(meetingMinutes());
        templates.add(generalAgenda());
        templates.add(boardAgenda());
        templates.add(shareholderAgenda());
        templates.add(resignation());
        return Immutables.copyOf(templates);
    }

    // ─── 휴가신청서 ───────────────────────────────────────────────────────────

    static SeededTemplate leaveRequest() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required("applicantName", FieldType.EMPLOYEE_REF,
                "신청자", "Applicant"));
        fields.add(FieldDefinition.required("department", FieldType.ORG_REF, "소속", "Department"));
        fields.add(FieldDefinition.of("rank", FieldType.TEXT, "직급", "Rank"));
        fields.add(FieldDefinition.required("leaveType", FieldType.TEXT,
                "휴가 구분", "Leave type"));
        fields.add(FieldDefinition.required("leaveStartDate", FieldType.DATE,
                "시작일", "Start date"));
        fields.add(FieldDefinition.required("leaveEndDate", FieldType.DATE, "종료일", "End date"));
        fields.add(FieldDefinition.required("leaveDays", FieldType.NUMBER,
                "사용 일수", "Days used"));
        fields.add(FieldDefinition.of("handoverTo", FieldType.EMPLOYEE_REF,
                "업무 인수자", "Handover to"));
        fields.add(FieldDefinition.of("contactDuringLeave", FieldType.TEXT,
                "휴가 중 연락처", "Contact during leave"));
        fields.add(FieldDefinition.of("reason", FieldType.MULTILINE_TEXT, "사유", "Reason"));

        InternalDoc ko = FormBuilder.create()
                .title("휴가신청서")
                .guidance("결재가 완료되면 사용 일수가 연차 원장에 기록됩니다. "
                        + "반차는 0.5일 단위로 적어 주십시오.")
                .row("신청자", "applicantName")
                .row("소속", "department")
                .row("직급", "rank")
                .row("휴가 구분", "leaveType")
                .row("시작일", "leaveStartDate")
                .row("종료일", "leaveEndDate")
                .row("사용 일수", "leaveDays")
                .row("업무 인수자", "handoverTo")
                .row("휴가 중 연락처", "contactDuringLeave")
                .heading("사유", 2)
                .row("사유", "reason")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Leave request")
                .guidance("Once approved, the days used are written to the leave ledger. "
                        + "Record half days as 0.5.")
                .row("Applicant", "applicantName")
                .row("Department", "department")
                .row("Rank", "rank")
                .row("Leave type", "leaveType")
                .row("Start date", "leaveStartDate")
                .row("End date", "leaveEndDate")
                .row("Days used", "leaveDays")
                .row("Handover to", "handoverTo")
                .row("Contact during leave", "contactDuringLeave")
                .heading("Reason", 2)
                .row("Reason", "reason")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        return new SeededTemplate(LEAVE_REQUEST, "leave-request", "휴가신청서", "Leave request",
                new DocumentFieldSchema(fields), bodies(ko, en));
    }

    // ─── 지출결의서 / 지출내역서 ──────────────────────────────────────────────

    /**
     * The expense form.
     *
     * <h2>Why the VAT is typed in rather than worked out</h2>
     *
     * <p>Net and VAT are separate fields and neither is derived from the other,
     * because in Korean practice they routinely disagree with the arithmetic and
     * the invoice is right: rounding is per line and per counterparty, a
     * 영세율 line carries 0 tax without being zero-rated by rate, and a 면세
     * line is outside VAT altogether. A form that computed VAT at 10% would
     * quietly overwrite what the 세금계산서 actually says, and the person who
     * found out would be the accountant reconciling a return.
     *
     * <p>So 과세 / 영세율 / 면세 are three different treatments, and none of them
     * is "rate = 0".
     */
    static SeededTemplate expenseApproval() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required("requesterName", FieldType.EMPLOYEE_REF,
                "신청자", "Requested by"));
        fields.add(FieldDefinition.required("costCentre", FieldType.ORG_REF,
                "귀속 부서", "Cost centre"));
        fields.add(FieldDefinition.required("expenseDate", FieldType.DATE,
                "지출일", "Expense date"));
        fields.add(FieldDefinition.of("paymentDueDate", FieldType.DATE,
                "지급 예정일", "Payment due"));
        fields.add(FieldDefinition.required("payeeName", FieldType.TEXT, "거래처", "Payee"));
        fields.add(FieldDefinition.of("payeeRegistrationNo", FieldType.TEXT,
                "사업자등록번호", "Business registration no."));
        fields.add(FieldDefinition.required("currency", FieldType.TEXT, "통화", "Currency"));
        fields.add(FieldDefinition.required("purpose", FieldType.MULTILINE_TEXT,
                "지출 목적", "Purpose"));
        for (int line = 1; line <= 3; line++) {
            fields.add(FieldDefinition.of("line" + line + "Description", FieldType.TEXT,
                    line + "행 적요", "Line " + line + " description"));
            fields.add(FieldDefinition.of("line" + line + "TaxTreatment", FieldType.TEXT,
                    line + "행 과세 구분", "Line " + line + " tax treatment"));
            fields.add(FieldDefinition.of("line" + line + "VatRate", FieldType.NUMBER,
                    line + "행 세율(%)", "Line " + line + " VAT rate (%)"));
            fields.add(FieldDefinition.of("line" + line + "NetAmount", FieldType.MONEY,
                    line + "행 공급가액", "Line " + line + " net"));
            fields.add(FieldDefinition.of("line" + line + "VatAmount", FieldType.MONEY,
                    line + "행 부가세액", "Line " + line + " VAT"));
            fields.add(FieldDefinition.of("line" + line + "GrossAmount", FieldType.MONEY,
                    line + "행 합계", "Line " + line + " gross"));
        }
        fields.add(FieldDefinition.required("totalNetAmount", FieldType.MONEY,
                "공급가액 합계", "Net total"));
        fields.add(FieldDefinition.required("totalVatAmount", FieldType.MONEY,
                "부가세액 합계", "VAT total"));
        fields.add(FieldDefinition.required("totalGrossAmount", FieldType.MONEY,
                "총계", "Gross total"));
        fields.add(FieldDefinition.of("receiptsAttached", FieldType.FILE, "증빙", "Receipts"));

        String[] headerKo = {"적요", "과세 구분", "세율(%)", "공급가액", "부가세액", "합계"};
        String[] headerEn = {"Description", "Tax treatment", "Rate (%)", "Net", "VAT", "Gross"};
        String[][] lineTags = new String[3][];
        for (int line = 1; line <= 3; line++) {
            lineTags[line - 1] = new String[] {
                "line" + line + "Description",
                "line" + line + "TaxTreatment",
                "line" + line + "VatRate",
                "line" + line + "NetAmount",
                "line" + line + "VatAmount",
                "line" + line + "GrossAmount",
            };
        }
        String[][] totalTagsKo = {{"", "", "", "totalNetAmount", "totalVatAmount",
            "totalGrossAmount"}};

        InternalDoc ko = FormBuilder.create()
                .title("지출결의서 / 지출내역서")
                .guidance("공급가액과 부가세액은 각각 입력합니다. 세액을 자동으로 계산하지 않습니다 — "
                        + "세금계산서에 적힌 금액을 그대로 적어 주십시오.")
                .row("신청자", "requesterName")
                .row("귀속 부서", "costCentre")
                .row("지출일", "expenseDate")
                .row("지급 예정일", "paymentDueDate")
                .row("거래처", "payeeName")
                .row("사업자등록번호", "payeeRegistrationNo")
                .row("통화", "currency")
                .heading("지출 내역", 2)
                .fieldTable(headerKo, lineTags)
                .heading("합계", 2)
                .fieldTable(new String[] {"", "", "", "공급가액 합계", "부가세액 합계", "총계"},
                        totalTagsKo)
                .heading("과세 구분 안내", 2)
                .bullet("과세: 부가가치세가 붙는 일반 거래입니다. 세율란에 적용 세율을 적어 주십시오.")
                .bullet("영세율: 세율이 0%인 과세 거래입니다. 매입세액 공제가 가능하므로 면세와 다릅니다.")
                .bullet("면세: 부가가치세 과세 대상이 아닌 거래입니다. 세율 0%가 아니라 과세 대상 자체가 "
                        + "아니므로 영세율과 구분해 주십시오.")
                .heading("지출 목적", 2)
                .row("지출 목적", "purpose")
                .row("증빙", "receiptsAttached")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Expense approval / expense report")
                .guidance("Enter the net and the VAT separately. Neither is calculated from the "
                        + "other — copy the figures from the tax invoice as they are printed.")
                .row("Requested by", "requesterName")
                .row("Cost centre", "costCentre")
                .row("Expense date", "expenseDate")
                .row("Payment due", "paymentDueDate")
                .row("Payee", "payeeName")
                .row("Business registration no.", "payeeRegistrationNo")
                .row("Currency", "currency")
                .heading("Lines", 2)
                .fieldTable(headerEn, lineTags)
                .heading("Totals", 2)
                .fieldTable(new String[] {"", "", "", "Net total", "VAT total", "Gross total"},
                        totalTagsKo)
                .heading("Tax treatments", 2)
                .bullet("Standard: VAT applies. Put the applicable rate in the rate column.")
                .bullet("Zero-rated: a taxable supply at 0%. Input VAT remains recoverable, which "
                        + "is what makes it different from exempt.")
                .bullet("Exempt: outside the scope of VAT altogether. Not a 0% rate — record it "
                        + "as exempt rather than as zero-rated.")
                .heading("Purpose", 2)
                .row("Purpose", "purpose")
                .row("Receipts", "receiptsAttached")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        return new SeededTemplate(EXPENSE_APPROVAL, "expense-approval",
                "지출결의서 / 지출내역서", "Expense approval / expense report",
                new DocumentFieldSchema(fields), bodies(ko, en));
    }

    // ─── 회의록 ──────────────────────────────────────────────────────────────

    static SeededTemplate meetingMinutes() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required("meetingTitle", FieldType.TEXT,
                "회의명", "Meeting"));
        fields.add(FieldDefinition.required("meetingDate", FieldType.BUSINESS_INSTANT,
                "일시", "Date and time"));
        fields.add(FieldDefinition.of("meetingPlace", FieldType.TEXT, "장소", "Location"));
        fields.add(FieldDefinition.required("chairperson", FieldType.EMPLOYEE_REF,
                "의장", "Chair"));
        fields.add(FieldDefinition.required("recorder", FieldType.EMPLOYEE_REF,
                "작성자", "Recorded by"));
        fields.add(FieldDefinition.required("attendees", FieldType.MULTILINE_TEXT,
                "참석자", "Attendees"));
        fields.add(FieldDefinition.of("absentees", FieldType.MULTILINE_TEXT,
                "불참자", "Absent"));
        fields.add(FieldDefinition.of("relatedAgenda", FieldType.TEXT,
                "관련 안건", "Related agenda"));
        fields.add(FieldDefinition.required("agendaSummary", FieldType.MULTILINE_TEXT,
                "논의 내용", "Discussion"));
        fields.add(FieldDefinition.required("resolutions", FieldType.MULTILINE_TEXT,
                "결의 사항", "Resolutions"));
        for (int item = 1; item <= 3; item++) {
            fields.add(FieldDefinition.of("action" + item + "Description", FieldType.TEXT,
                    item + "번 실행 항목", "Action " + item));
            fields.add(FieldDefinition.of("action" + item + "Owner", FieldType.EMPLOYEE_REF,
                    item + "번 담당자", "Action " + item + " owner"));
            fields.add(FieldDefinition.of("action" + item + "DueDate", FieldType.DATE,
                    item + "번 기한", "Action " + item + " due"));
        }

        String[][] actionTags = new String[3][];
        for (int item = 1; item <= 3; item++) {
            actionTags[item - 1] = new String[] {
                "action" + item + "Description",
                "action" + item + "Owner",
                "action" + item + "DueDate",
            };
        }

        InternalDoc ko = FormBuilder.create()
                .title("회의록")
                .guidance("실행 항목에는 반드시 담당자와 기한을 적어 주십시오. "
                        + "담당자가 없는 실행 항목은 실행되지 않습니다.")
                .row("회의명", "meetingTitle")
                .row("일시", "meetingDate")
                .row("장소", "meetingPlace")
                .row("의장", "chairperson")
                .row("작성자", "recorder")
                .row("관련 안건", "relatedAgenda")
                .heading("참석 현황", 2)
                .row("참석자", "attendees")
                .row("불참자", "absentees")
                .heading("논의 내용", 2)
                .row("논의 내용", "agendaSummary")
                .heading("결의 사항", 2)
                .row("결의 사항", "resolutions")
                .heading("실행 항목", 2)
                .fieldTable(new String[] {"내용", "담당자", "기한"}, actionTags)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Meeting minutes")
                .guidance("Every action item needs an owner and a due date. An action item with "
                        + "no owner does not happen.")
                .row("Meeting", "meetingTitle")
                .row("Date and time", "meetingDate")
                .row("Location", "meetingPlace")
                .row("Chair", "chairperson")
                .row("Recorded by", "recorder")
                .row("Related agenda", "relatedAgenda")
                .heading("Attendance", 2)
                .row("Attendees", "attendees")
                .row("Absent", "absentees")
                .heading("Discussion", 2)
                .row("Discussion", "agendaSummary")
                .heading("Resolutions", 2)
                .row("Resolutions", "resolutions")
                .heading("Action items", 2)
                .fieldTable(new String[] {"Item", "Owner", "Due"}, actionTags)
                .build("docx");

        Map<String, InternalDoc> bodies = bodies(ko, en);
        bodies.put(SeededTemplate.LOCALE_KO_MDV, meetingMinutesMdv(true));
        bodies.put(SeededTemplate.LOCALE_EN_MDV, meetingMinutesMdv(false));

        return new SeededTemplate(MEETING_MINUTES, "meeting-minutes", "회의록",
                "Meeting minutes", new DocumentFieldSchema(fields), bodies);
    }

    /**
     * The mdv companion.
     *
     * <p>§6.10 asks the seeded set to demonstrate the format on first run, and a
     * chart is the demonstration. This one is deliberately mundane — action
     * items outstanding per owner — because the point is that a chart is plain
     * text in the source and therefore produces a readable diff between two
     * revisions of the same minutes.
     */
    private static InternalDoc meetingMinutesMdv(boolean korean) {
        FormBuilder builder = FormBuilder.create();
        if (korean) {
            builder.title("회의록")
                   .paragraph("이 문서는 mdv 형식입니다. 아래 표를 고치면 차트가 함께 바뀌고, "
                           + "두 판(版)의 차이가 글자 그대로 비교됩니다.")
                   .heading("실행 항목 현황", 2)
                   .mdvChart("```mdv bar\ntitle: 담당자별 미완료 실행 항목\nx: 담당자\ny: 건수\n"
                           + "---\n담당자,건수\n김민준,3\n이서연,2\n박지호,1\n```")
                   .heading("결의 사항", 2)
                   .bullet("의결된 사항은 결의 사항란에 한 줄에 하나씩 적어 주십시오.");
        } else {
            builder.title("Meeting minutes")
                   .paragraph("This document is in mdv. Edit the table below and the chart "
                           + "changes with it, and two revisions diff as text.")
                   .heading("Action items outstanding", 2)
                   .mdvChart("```mdv bar\ntitle: Open action items by owner\nx: Owner\ny: Count\n"
                           + "---\nOwner,Count\nKim Minjun,3\nLee Seoyeon,2\nPark Jiho,1\n```")
                   .heading("Resolutions", 2)
                   .bullet("Record one resolution per line.");
        }
        return builder.build("mdv");
    }

    // ─── 일반안건 ────────────────────────────────────────────────────────────

    static SeededTemplate generalAgenda() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.of("agendaNo", FieldType.TEXT, "안건 번호", "Agenda no."));
        fields.add(FieldDefinition.required("agendaTitle", FieldType.TEXT, "안건명", "Title"));
        fields.add(FieldDefinition.required("proposer", FieldType.EMPLOYEE_REF,
                "제안자", "Proposed by"));
        fields.add(FieldDefinition.of("proposingDepartment", FieldType.ORG_REF,
                "제안 부서", "Proposing department"));
        fields.add(FieldDefinition.required("proposedDate", FieldType.DATE,
                "제안일", "Date proposed"));
        fields.add(FieldDefinition.of("background", FieldType.MULTILINE_TEXT,
                "제안 배경", "Background"));
        fields.add(FieldDefinition.required("proposal", FieldType.MULTILINE_TEXT,
                "제안 내용", "Proposal"));
        fields.add(FieldDefinition.of("budgetImpact", FieldType.MONEY,
                "예산 영향", "Budget impact"));
        fields.add(FieldDefinition.of("decision", FieldType.MULTILINE_TEXT,
                "결정 사항", "Decision"));

        InternalDoc ko = FormBuilder.create()
                .title("일반안건")
                .row("안건 번호", "agendaNo")
                .row("안건명", "agendaTitle")
                .row("제안자", "proposer")
                .row("제안 부서", "proposingDepartment")
                .row("제안일", "proposedDate")
                .row("예산 영향", "budgetImpact")
                .heading("제안 배경", 2)
                .row("제안 배경", "background")
                .heading("제안 내용", 2)
                .row("제안 내용", "proposal")
                .heading("결정 사항", 2)
                .row("결정 사항", "decision")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("General agenda item")
                .row("Agenda no.", "agendaNo")
                .row("Title", "agendaTitle")
                .row("Proposed by", "proposer")
                .row("Proposing department", "proposingDepartment")
                .row("Date proposed", "proposedDate")
                .row("Budget impact", "budgetImpact")
                .heading("Background", 2)
                .row("Background", "background")
                .heading("Proposal", 2)
                .row("Proposal", "proposal")
                .heading("Decision", 2)
                .row("Decision", "decision")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        return new SeededTemplate(GENERAL_AGENDA, "general-agenda", "일반안건",
                "General agenda item", new DocumentFieldSchema(fields), bodies(ko, en));
    }

    // ─── 이사회안건 ──────────────────────────────────────────────────────────

    /**
     * The board agenda.
     *
     * <h2>Recusal is not abstention</h2>
     *
     * <p>A director with a 특별이해관계 in the matter cannot vote on it at all.
     * Their shares of the decision are excluded from the count rather than
     * recorded as an abstention, and the quorum arithmetic changes accordingly.
     * Recording it as 기권 would produce a minute that looks lawful and is not,
     * which is why the recusal flag is its own field rather than a third vote
     * value.
     */
    static SeededTemplate boardAgenda() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.of("agendaNo", FieldType.TEXT, "의안 번호", "Agenda no."));
        fields.add(FieldDefinition.required("agendaTitle", FieldType.TEXT, "의안명", "Title"));
        fields.add(FieldDefinition.required("meetingDate", FieldType.BUSINESS_INSTANT,
                "개최 일시", "Date and time"));
        fields.add(FieldDefinition.required("totalDirectors", FieldType.NUMBER,
                "재적 이사 수", "Directors in office"));
        fields.add(FieldDefinition.required("directorsPresent", FieldType.NUMBER,
                "출석 이사 수", "Directors present"));
        fields.add(FieldDefinition.required("quorumMet", FieldType.TEXT,
                "성립 여부", "Quorum met"));
        fields.add(FieldDefinition.required("proposal", FieldType.MULTILINE_TEXT,
                "의안 내용", "Proposal"));
        for (int seat = 1; seat <= 5; seat++) {
            fields.add(FieldDefinition.of("director" + seat + "Name", FieldType.EMPLOYEE_REF,
                    seat + "번 이사", "Director " + seat));
            fields.add(FieldDefinition.of("director" + seat + "Vote", FieldType.TEXT,
                    seat + "번 의결", "Director " + seat + " vote"));
            fields.add(FieldDefinition.of("director" + seat + "Recused", FieldType.TEXT,
                    seat + "번 특별이해관계자 제척", "Director " + seat + " recused"));
        }
        fields.add(FieldDefinition.required("votesFor", FieldType.NUMBER, "찬성", "For"));
        fields.add(FieldDefinition.required("votesAgainst", FieldType.NUMBER, "반대", "Against"));
        fields.add(FieldDefinition.required("votesAbstain", FieldType.NUMBER, "기권", "Abstain"));
        fields.add(FieldDefinition.required("resolutionOutcome", FieldType.TEXT,
                "의결 결과", "Outcome"));

        String[][] voteTags = new String[5][];
        for (int seat = 1; seat <= 5; seat++) {
            voteTags[seat - 1] = new String[] {
                "director" + seat + "Name",
                "director" + seat + "Vote",
                "director" + seat + "Recused",
            };
        }

        InternalDoc ko = FormBuilder.create()
                .title("이사회안건")
                .guidance("의결란에는 찬성·반대·기권 중 하나를 적어 주십시오. "
                        + "특별이해관계 있는 이사는 의결에 참가할 수 없으므로, 기권이 아니라 "
                        + "제척란에 '예'를 적고 의결란은 비워 주십시오.")
                .row("의안 번호", "agendaNo")
                .row("의안명", "agendaTitle")
                .row("개최 일시", "meetingDate")
                .heading("성립 요건", 2)
                .row("재적 이사 수", "totalDirectors")
                .row("출석 이사 수", "directorsPresent")
                .row("성립 여부", "quorumMet")
                .heading("의안 내용", 2)
                .row("의안 내용", "proposal")
                .heading("이사별 의결", 2)
                .fieldTable(new String[] {"이사", "의결", "특별이해관계자 제척"}, voteTags)
                .heading("집계", 2)
                .row("찬성", "votesFor")
                .row("반대", "votesAgainst")
                .row("기권", "votesAbstain")
                .row("의결 결과", "resolutionOutcome")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Board agenda item")
                .guidance("Record each vote as for, against or abstain. A director with a special "
                        + "interest in the matter may not vote at all: mark them recused and leave "
                        + "the vote blank rather than recording an abstention.")
                .row("Agenda no.", "agendaNo")
                .row("Title", "agendaTitle")
                .row("Date and time", "meetingDate")
                .heading("Quorum", 2)
                .row("Directors in office", "totalDirectors")
                .row("Directors present", "directorsPresent")
                .row("Quorum met", "quorumMet")
                .heading("Proposal", 2)
                .row("Proposal", "proposal")
                .heading("Vote by director", 2)
                .fieldTable(new String[] {"Director", "Vote", "Recused (special interest)"},
                        voteTags)
                .heading("Tally", 2)
                .row("For", "votesFor")
                .row("Against", "votesAgainst")
                .row("Abstain", "votesAbstain")
                .row("Outcome", "resolutionOutcome")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        Map<String, InternalDoc> bodies = bodies(ko, en);
        bodies.put(SeededTemplate.LOCALE_KO_MDV, boardAgendaMdv(true));
        bodies.put(SeededTemplate.LOCALE_EN_MDV, boardAgendaMdv(false));

        return new SeededTemplate(BOARD_AGENDA, "board-agenda", "이사회안건",
                "Board agenda item", new DocumentFieldSchema(fields), bodies);
    }

    private static InternalDoc boardAgendaMdv(boolean korean) {
        FormBuilder builder = FormBuilder.create();
        if (korean) {
            builder.title("이사회안건")
                   .paragraph("이 문서는 mdv 형식입니다. 의결 결과를 표로 적으면 차트가 함께 "
                           + "만들어지며, 승인된 판본은 그대로 PDF에 보존됩니다.")
                   .heading("의결 집계", 2)
                   .mdvChart("```mdv pie\ntitle: 의결 집계\n---\n구분,표수\n찬성,4\n반대,1\n"
                           + "기권,0\n제척,1\n```")
                   .heading("의결 정족수 추이", 2)
                   .mdvChart("```mdv line\ntitle: 회차별 출석 이사 수\nx: 회차\ny: 인원\n"
                           + "---\n회차,출석,재적\n1분기,5,6\n2분기,6,6\n3분기,4,6\n```")
                   .paragraph("제척은 기권과 다릅니다. 집계에서 제외되며 정족수 계산도 달라집니다.");
        } else {
            builder.title("Board agenda item")
                   .paragraph("This document is in mdv. Write the tally as a table and the chart "
                           + "follows; the approved revision is preserved as-is in the PDF.")
                   .heading("Vote tally", 2)
                   .mdvChart("```mdv pie\ntitle: Vote tally\n---\nOutcome,Votes\nFor,4\n"
                           + "Against,1\nAbstain,0\nRecused,1\n```")
                   .heading("Attendance across meetings", 2)
                   .mdvChart("```mdv line\ntitle: Directors present by quarter\nx: Quarter\n"
                           + "y: Directors\n---\nQuarter,Present,In office\nQ1,5,6\nQ2,6,6\n"
                           + "Q3,4,6\n```")
                   .paragraph("Recusal is not abstention: it is excluded from the tally and it "
                           + "changes the quorum arithmetic.");
        }
        return builder.build("mdv");
    }

    // ─── 주주총회안건 ────────────────────────────────────────────────────────

    /**
     * The shareholder meeting agenda.
     *
     * <h2>Votes are counted in shares, not in people</h2>
     *
     * <p>Every count on this form is a share count. The distinction between an
     * ordinary and a special resolution is a different threshold against those
     * same share counts, which is why the resolution class is a field rather
     * than two templates: the arithmetic is the same shape and only the bar
     * moves.
     */
    static SeededTemplate shareholderAgenda() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.of("agendaNo", FieldType.TEXT, "의안 번호", "Agenda no."));
        fields.add(FieldDefinition.required("agendaTitle", FieldType.TEXT, "의안명", "Title"));
        fields.add(FieldDefinition.required("meetingDate", FieldType.BUSINESS_INSTANT,
                "개최 일시", "Date and time"));
        fields.add(FieldDefinition.required("resolutionClass", FieldType.TEXT,
                "결의 종류", "Resolution class"));
        fields.add(FieldDefinition.required("totalSharesIssued", FieldType.NUMBER,
                "발행주식총수", "Shares issued"));
        fields.add(FieldDefinition.required("votingShares", FieldType.NUMBER,
                "의결권 있는 주식 수", "Shares carrying votes"));
        fields.add(FieldDefinition.required("sharesPresent", FieldType.NUMBER,
                "출석 주식 수", "Shares represented"));
        fields.add(FieldDefinition.required("quorumMet", FieldType.TEXT,
                "성립 여부", "Quorum met"));
        fields.add(FieldDefinition.required("proposal", FieldType.MULTILINE_TEXT,
                "의안 내용", "Proposal"));
        fields.add(FieldDefinition.required("sharesFor", FieldType.NUMBER,
                "찬성 주식 수", "Shares for"));
        fields.add(FieldDefinition.required("sharesAgainst", FieldType.NUMBER,
                "반대 주식 수", "Shares against"));
        fields.add(FieldDefinition.required("sharesAbstain", FieldType.NUMBER,
                "기권 주식 수", "Shares abstaining"));
        fields.add(FieldDefinition.required("resolutionOutcome", FieldType.TEXT,
                "의결 결과", "Outcome"));

        InternalDoc ko = FormBuilder.create()
                .title("주주총회안건")
                .guidance("모든 수치는 주식 수입니다. 주주 수가 아닙니다.")
                .row("의안 번호", "agendaNo")
                .row("의안명", "agendaTitle")
                .row("개최 일시", "meetingDate")
                .row("결의 종류", "resolutionClass")
                .heading("결의 요건", 2)
                .bullet("보통결의: 출석 주주 의결권의 과반수와 발행주식총수의 4분의 1 이상의 찬성이 "
                        + "필요합니다.")
                .bullet("특별결의: 출석 주주 의결권의 3분의 2 이상과 발행주식총수의 3분의 1 이상의 "
                        + "찬성이 필요합니다.")
                .guidance("정관에서 요건을 달리 정한 경우에는 정관이 우선합니다. 위 기준은 회사에서 "
                        + "고쳐 쓰실 수 있습니다.")
                .heading("성립 요건", 2)
                .row("발행주식총수", "totalSharesIssued")
                .row("의결권 있는 주식 수", "votingShares")
                .row("출석 주식 수", "sharesPresent")
                .row("성립 여부", "quorumMet")
                .heading("의안 내용", 2)
                .row("의안 내용", "proposal")
                .heading("의결 집계 (주식 수)", 2)
                .row("찬성 주식 수", "sharesFor")
                .row("반대 주식 수", "sharesAgainst")
                .row("기권 주식 수", "sharesAbstain")
                .row("의결 결과", "resolutionOutcome")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Shareholder meeting agenda item")
                .guidance("Every figure here is a number of shares, not a number of shareholders.")
                .row("Agenda no.", "agendaNo")
                .row("Title", "agendaTitle")
                .row("Date and time", "meetingDate")
                .row("Resolution class", "resolutionClass")
                .heading("Thresholds", 2)
                .bullet("Ordinary resolution: a majority of the votes represented, and at least "
                        + "one quarter of all shares issued.")
                .bullet("Special resolution: at least two thirds of the votes represented, and at "
                        + "least one third of all shares issued.")
                .guidance("Where the articles set different thresholds, the articles govern. "
                        + "These lines are yours to edit.")
                .heading("Quorum", 2)
                .row("Shares issued", "totalSharesIssued")
                .row("Shares carrying votes", "votingShares")
                .row("Shares represented", "sharesPresent")
                .row("Quorum met", "quorumMet")
                .heading("Proposal", 2)
                .row("Proposal", "proposal")
                .heading("Tally (in shares)", 2)
                .row("Shares for", "sharesFor")
                .row("Shares against", "sharesAgainst")
                .row("Shares abstaining", "sharesAbstain")
                .row("Outcome", "resolutionOutcome")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        return new SeededTemplate(SHAREHOLDER_AGENDA, "shareholder-agenda", "주주총회안건",
                "Shareholder meeting agenda item", new DocumentFieldSchema(fields),
                bodies(ko, en));
    }

    // ─── 사직서 ──────────────────────────────────────────────────────────────

    static SeededTemplate resignation() {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        fields.add(FieldDefinition.required("employeeName", FieldType.EMPLOYEE_REF,
                "성명", "Name"));
        fields.add(FieldDefinition.required("department", FieldType.ORG_REF, "소속", "Department"));
        fields.add(FieldDefinition.of("rank", FieldType.TEXT, "직급", "Rank"));
        fields.add(FieldDefinition.of("hireDate", FieldType.DATE, "입사일", "Hire date"));
        fields.add(FieldDefinition.required("lastWorkingDate", FieldType.DATE,
                "마지막 근무일", "Last working day"));
        fields.add(FieldDefinition.required("resignationDate", FieldType.DATE,
                "퇴직일", "Termination date"));
        fields.add(FieldDefinition.required("handoverTo", FieldType.EMPLOYEE_REF,
                "업무 인수자", "Handover to"));
        fields.add(FieldDefinition.of("reason", FieldType.MULTILINE_TEXT, "사유", "Reason"));
        fields.add(FieldDefinition.of("contactAfterLeaving", FieldType.TEXT,
                "퇴직 후 연락처", "Contact after leaving"));

        InternalDoc ko = FormBuilder.create()
                .title("사직서")
                .guidance("결재가 완료되면 퇴직 절차 점검표가 만들어집니다. 계정은 퇴직일이 지나면 "
                        + "비활성화되므로, 마지막 근무일과 퇴직일을 정확히 적어 주십시오.")
                .row("성명", "employeeName")
                .row("소속", "department")
                .row("직급", "rank")
                .row("입사일", "hireDate")
                .row("마지막 근무일", "lastWorkingDate")
                .row("퇴직일", "resignationDate")
                .row("업무 인수자", "handoverTo")
                .row("퇴직 후 연락처", "contactAfterLeaving")
                .heading("사유", 2)
                .row("사유", "reason")
                .heading("결재", 2)
                .approvalBlock(APPROVAL_ROLES_KO)
                .build("docx");

        InternalDoc en = FormBuilder.create()
                .title("Resignation")
                .guidance("Approval creates the offboarding checklist. The account is deactivated "
                        + "once the termination date has passed, so record the last working day "
                        + "and the termination date accurately.")
                .row("Name", "employeeName")
                .row("Department", "department")
                .row("Rank", "rank")
                .row("Hire date", "hireDate")
                .row("Last working day", "lastWorkingDate")
                .row("Termination date", "resignationDate")
                .row("Handover to", "handoverTo")
                .row("Contact after leaving", "contactAfterLeaving")
                .heading("Reason", 2)
                .row("Reason", "reason")
                .heading("Approval", 2)
                .approvalBlock(APPROVAL_ROLES_EN)
                .build("docx");

        return new SeededTemplate(RESIGNATION, "resignation", "사직서", "Resignation",
                new DocumentFieldSchema(fields), bodies(ko, en));
    }

    private static Map<String, InternalDoc> bodies(InternalDoc ko, InternalDoc en) {
        Map<String, InternalDoc> bodies = new LinkedHashMap<String, InternalDoc>();
        bodies.put(SeededTemplate.LOCALE_KO, ko);
        bodies.put(SeededTemplate.LOCALE_EN, en);
        return bodies;
    }
}
