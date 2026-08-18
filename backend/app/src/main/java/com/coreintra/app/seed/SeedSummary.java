package com.coreintra.app.seed;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;

/**
 * What a seed run produced, in the shape the console prints and the tests assert on.
 *
 * <p>A seed that only says "done" is a seed nobody checks. The summary names the counts and
 * the two or three facts that are easy to get wrong - whether the ledger balances, whether the
 * night shift landed on the day it began, how many documents are waiting in the demo account's
 * inbox - so that a person running {@code make seed} can see the demo is real before opening
 * the browser, and so the second run can prove it changed nothing.
 */
public final class SeedSummary {

    private boolean alreadySeeded;
    private String hqCompanyId;
    private String subsidiaryCompanyId;
    private int employeeCount;
    private int accountCount;
    private int documentCount;
    private int attendanceRecordCount;
    private String demoAccountId;
    private int demoInboxSize;
    private int nightShiftOffsetSeconds;
    private String bookId;
    private boolean ledgerBalanced;
    private boolean ledgerSkipped;
    private final List<DemoCredential> credentials = new ArrayList<DemoCredential>();

    public boolean isAlreadySeeded() {
        return alreadySeeded;
    }

    void setAlreadySeeded(boolean value) {
        this.alreadySeeded = value;
    }

    public String hqCompanyId() {
        return hqCompanyId;
    }

    void setHqCompanyId(String value) {
        this.hqCompanyId = value;
    }

    public String subsidiaryCompanyId() {
        return subsidiaryCompanyId;
    }

    void setSubsidiaryCompanyId(String value) {
        this.subsidiaryCompanyId = value;
    }

    public int employeeCount() {
        return employeeCount;
    }

    void setEmployeeCount(int value) {
        this.employeeCount = value;
    }

    public int accountCount() {
        return accountCount;
    }

    void setAccountCount(int value) {
        this.accountCount = value;
    }

    public int documentCount() {
        return documentCount;
    }

    void setDocumentCount(int value) {
        this.documentCount = value;
    }

    public int attendanceRecordCount() {
        return attendanceRecordCount;
    }

    void setAttendanceRecordCount(int value) {
        this.attendanceRecordCount = value;
    }

    public String demoAccountId() {
        return demoAccountId;
    }

    void setDemoAccountId(String value) {
        this.demoAccountId = value;
    }

    public int demoInboxSize() {
        return demoInboxSize;
    }

    void setDemoInboxSize(int value) {
        this.demoInboxSize = value;
    }

    /** The offset of the end of the night shift, in seconds past the start of its business day. */
    public int nightShiftOffsetSeconds() {
        return nightShiftOffsetSeconds;
    }

    void setNightShiftOffsetSeconds(int value) {
        this.nightShiftOffsetSeconds = value;
    }

    public String bookId() {
        return bookId;
    }

    void setBookId(String value) {
        this.bookId = value;
    }

    public boolean isLedgerBalanced() {
        return ledgerBalanced;
    }

    void setLedgerBalanced(boolean value) {
        this.ledgerBalanced = value;
    }

    /** True when the accounting module is switched off and the demo has no books at all. */
    public boolean isLedgerSkipped() {
        return ledgerSkipped;
    }

    void setLedgerSkipped(boolean value) {
        this.ledgerSkipped = value;
    }

    /** The accounts a demo can sign in as, with the credentials that were shown once. */
    public List<DemoCredential> credentials() {
        return Immutables.copyOf(credentials);
    }

    void addCredential(DemoCredential credential) {
        credentials.add(credential);
    }

    /** The recovery codes for one seeded account, or empty if this run did not enrol it. */
    public List<String> recoveryCodesFor(String username) {
        for (DemoCredential credential : credentials) {
            if (credential.username().equals(username)) {
                return credential.recoveryCodes();
            }
        }
        return Immutables.listOf();
    }

    /**
     * One demo login. Holds a working authenticator secret and one-time codes, which is why it
     * only ever exists inside a seed run and is printed rather than stored anywhere.
     */
    public static final class DemoCredential {

        private final String username;
        private final String describedAs;
        private final String otpauthUri;
        private final List<String> recoveryCodes;

        DemoCredential(String username, String describedAs, String otpauthUri,
                List<String> recoveryCodes) {
            this.username = username;
            this.describedAs = describedAs;
            this.otpauthUri = otpauthUri;
            this.recoveryCodes = Immutables.copyOf(recoveryCodes);
        }

        public String username() {
            return username;
        }

        public String describedAs() {
            return describedAs;
        }

        public String otpauthUri() {
            return otpauthUri;
        }

        public List<String> recoveryCodes() {
            return recoveryCodes;
        }
    }

    /** The console report. Korean, because the people running a demo installation read Korean. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append('\n');
        text.append("──────────────────────────────────────────────────────────────\n");
        if (alreadySeeded) {
            text.append(" 데모 시드: 이미 적재되어 있어 아무것도 변경하지 않았습니다.\n");
        } else {
            text.append(" 데모 시드: 적재를 완료하였습니다.\n");
        }
        text.append("──────────────────────────────────────────────────────────────\n");
        text.append(" 법인       : ").append(DemoCompany.HQ_NAME_KO)
                .append(" (").append(DemoCompany.HQ_REQUIRED_REPRESENTATIVE_APPROVALS).append('/')
                .append(DemoCompany.HQ_DESIGNATED_REPRESENTATIVES).append(" 공동대표)\n");
        text.append("              ").append(DemoCompany.SUBSIDIARY_NAME_KO)
                .append(" (자회사, 각자대표)\n");
        text.append(" 임직원     : ").append(employeeCount).append("명, 로그인 계정 ")
                .append(accountCount).append("개\n");
        text.append(" 근태       : ").append(attendanceRecordCount).append("건 (")
                .append(DemoCompany.ATTENDANCE_MONTH).append("), 야간근무 종료 오프셋 ")
                .append(nightShiftOffsetSeconds).append("초\n");
        text.append(" 결재       : ").append(documentCount).append("건, 데모 계정 미결함 ")
                .append(demoInboxSize).append("건\n");
        if (ledgerSkipped) {
            text.append(" 회계       : 모듈이 꺼져 있어 장부를 만들지 않았습니다.\n");
        } else {
            text.append(" 회계       : ").append(DemoCompany.BOOK_NAME).append(", 대차 ")
                    .append(ledgerBalanced ? "일치합니다" : "불일치합니다").append('\n');
        }
        text.append(" 데모 계정  : ").append(DemoCompany.DEMO_USERNAME)
                .append(" (국내영업팀 부장 박지훈) — 이 계정으로 로그인하시면 결재할 문서가 있습니다.\n");
        text.append("──────────────────────────────────────────────────────────────\n");
        if (credentials.isEmpty()) {
            text.append(" 자격 증명은 최초 적재 때 한 번만 출력됩니다. 다시 필요하시면 계정 화면에서\n");
            text.append(" 인증기를 새로 등록해 주십시오. 기존 등록은 그때 무효가 됩니다.\n");
        } else {
            text.append(" 데모 자격 증명 — 이 설치본 전용이며, 파일로 저장되지 않습니다.\n");
            text.append(" 실제 설치(seed 프로파일이 아닌 경우)에서는 절대 출력되지 않습니다.\n\n");
            for (DemoCredential credential : credentials) {
                text.append("  ").append(credential.username()).append("  (")
                        .append(credential.describedAs()).append(")\n");
                text.append("    otpauth : ").append(credential.otpauthUri()).append('\n');
                List<String> codes = credential.recoveryCodes();
                for (int i = 0; i < codes.size(); i++) {
                    text.append(i % 4 == 0 ? "    복구코드: " : "  ").append(codes.get(i));
                    if (i % 4 == 3 || i == codes.size() - 1) {
                        text.append('\n');
                    }
                }
                text.append('\n');
            }
        }
        text.append("──────────────────────────────────────────────────────────────\n");
        return text.toString();
    }
}
