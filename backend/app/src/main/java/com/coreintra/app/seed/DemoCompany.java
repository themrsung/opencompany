package com.coreintra.app.seed;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The demo installation, written down once.
 *
 * <p>Everything the seed creates is described here rather than scattered through the
 * builders, for two reasons. The integration test has to be able to name the rows it
 * asserts on - the demo account, the night-shift employee, the book - without repeating
 * the literals and drifting from them. And a demo company is a piece of copy as much as a
 * piece of data: names, units and document titles read like a real Korean SME, and that is
 * easier to keep true when they sit in one table instead of forty {@code create} calls.
 *
 * <h2>Why the dates are fixed rather than relative to now</h2>
 *
 * <p>A seed whose data moves with the wall clock cannot be asserted on precisely, and its
 * second run disagrees with its first if midnight passes in between. The demo therefore has
 * a fixed "today" and a fixed attendance month. The cost is that the demo ages; the benefit
 * is that {@link #NIGHT_SHIFT_DATE} means the same thing to the seed, to the test and to
 * whoever is reading the screen.
 */
public final class DemoCompany {

    private DemoCompany() {
    }

    /** The business date the demo is staged as of. Every "current" document is on or before it. */
    public static final LocalDate TODAY = LocalDate.of(2026, 8, 18);

    /** The complete month of attendance the demo carries. */
    public static final YearMonth ATTENDANCE_MONTH = YearMonth.of(2026, 7);

    /**
     * The shift that starts at 22:00 and ends at 03:00 the next morning. It is stored on the
     * day it began with an offset of {@code 97200} seconds, which is the whole point of
     * {@code BusinessInstant}: the night belongs to Thursday even though the clock says Friday.
     */
    public static final LocalDate NIGHT_SHIFT_DATE = LocalDate.of(2026, 7, 16);

    public static final int NIGHT_SHIFT_START_HOUR = 22;
    public static final int NIGHT_SHIFT_END_HOUR = 27;

    /** The day 김민준 becomes a 과장. Documents dated before it still resolve him as a 대리. */
    public static final LocalDate PROMOTION_DATE = LocalDate.of(2026, 8, 1);

    /** Ordinary office hours, as offsets from the start of the business day. */
    public static final int WORK_START_HOUR = 9;
    public static final int WORK_END_HOUR = 18;

    // ---------------------------------------------------------------- companies

    public static final String HQ_CODE = "HANBIT";
    public static final String HQ_NAME_KO = "주식회사 한빛솔루션";
    public static final String HQ_NAME_EN = "Hanbit Solutions Co., Ltd.";
    public static final String HQ_REGISTRATION_NUMBER = "220-81-45678";
    public static final LocalDate HQ_ESTABLISHED_ON = LocalDate.of(2018, 3, 2);

    public static final String SUBSIDIARY_CODE = "HANBIT-P";
    public static final String SUBSIDIARY_NAME_KO = "한빛정밀 주식회사";
    public static final String SUBSIDIARY_NAME_EN = "Hanbit Precision Co., Ltd.";
    public static final String SUBSIDIARY_REGISTRATION_NUMBER = "310-86-11223";
    public static final LocalDate SUBSIDIARY_ESTABLISHED_ON = LocalDate.of(2021, 6, 15);

    public static final String BASE_CURRENCY_CODE = "KRW";

    /** 본사 is a 공동대표 company: two of the three registered representatives must sign. */
    public static final int HQ_REQUIRED_REPRESENTATIVE_APPROVALS = 2;
    public static final int HQ_DESIGNATED_REPRESENTATIVES = 3;

    /** 자회사 runs 각자대표: either representative can sign alone. */
    public static final int SUBSIDIARY_DESIGNATED_REPRESENTATIVES = 2;

    /** The date both representation arrangements take effect from. */
    public static final LocalDate REPRESENTATION_EFFECTIVE_FROM = LocalDate.of(2024, 1, 1);

    // ---------------------------------------------------------------- accounts

    /**
     * The account the seed itself acts as for structural work. It is a master account and it
     * is the only account whose grants are written without going through
     * {@code PermissionGrantService} - see {@link SeedBootstrap}.
     */
    public static final String OPERATOR_USERNAME = "seed.operator";
    public static final String OPERATOR_DISPLAY_NAME = "데모 시드 운영자";

    /** The account a demo is meant to sign in as: a 부장 with documents waiting on him. */
    public static final String DEMO_USERNAME = "park.jihoon";

    public static final String EMAIL_DOMAIN = "hanbit.example.com";

    // ---------------------------------------------------------------- ledger

    public static final String BOOK_NAME = "2026 본사 총계정원장";

    // ---------------------------------------------------------------- people of note

    /** 국내영업팀 부장. The demo login, and the approver of most of what is in flight. */
    public static final String DEMO_EMPLOYEE_NUMBER = "20190041";
    /** 개발1팀 차장, who worked the night of the 16th. */
    public static final String NIGHT_SHIFT_EMPLOYEE_NUMBER = "20200412";
    /** 국내영업팀 대리 → 과장, promoted mid-period. Drafts most of the demo's documents. */
    public static final String PROMOTED_EMPLOYEE_NUMBER = "20230043";
    /** 인사팀 부장. Attendance is recorded under her authority, not the operator's. */
    public static final String HR_LEAD_EMPLOYEE_NUMBER = "20190031";
    /** 재무팀 부장. The ledger is opened and posted under his authority. */
    public static final String FINANCE_LEAD_EMPLOYEE_NUMBER = "20190021";
    /** 대표이사 김도현, who also holds the installation's human master account. */
    public static final String MASTER_EMPLOYEE_NUMBER = "20180001";
    /** 영업본부 이사, the second signature on most of what 국내영업팀 files. */
    public static final String SALES_DIRECTOR_EMPLOYEE_NUMBER = "20180040";
    /** 국내영업팀 사원, who drafts the document left waiting on the demo account. */
    public static final String JUNIOR_SALES_EMPLOYEE_NUMBER = "20240044";

    // ---------------------------------------------------------------- catalogues

    /**
     * The rank ladder, matching the defaults in {@code V6__seed_defaults.sql} exactly.
     *
     * <p>The migration seeds those rows for companies that exist when it runs, which on a
     * fresh installation is none of them. A company created afterwards - which is every
     * company, including these two - starts with an empty catalogue, so the seed has to lay
     * the same ladder itself. Codes, labels and seniority are copied rather than invented so
     * that a demo installation and a migrated one describe the same organisation.
     *
     * <p>Columns: code, 라벨, label, seniority, representative.
     */
    public static final String[][] RANKS = {
        {"SAWON", "사원", "Staff", "10", "false"},
        {"DAERI", "대리", "Assistant Manager", "20", "false"},
        {"GWAJANG", "과장", "Manager", "30", "false"},
        {"CHAJANG", "차장", "Deputy General Mgr", "40", "false"},
        {"BUJANG", "부장", "General Manager", "50", "false"},
        {"ISA", "이사", "Director", "60", "false"},
        {"DAEPYO", "대표", "Representative", "70", "true"},
    };

    /** 직무, again matching the migration defaults. Columns: code, 라벨, label. */
    public static final String[][] JOB_FUNCTIONS = {
        {"ACCOUNTING", "회계", "Accounting"},
        {"HR", "인사", "Human Resources"},
        {"DEV", "개발", "Engineering"},
        {"SALES", "영업", "Sales"},
        {"LEGAL", "법무", "Legal"},
        {"GA", "총무", "General Affairs"},
    };

    /**
     * The six built-in attendance statuses, again copied from {@code V6__seed_defaults.sql}
     * for the same reason. Columns: code, 라벨, label, colour, icon, countsAsWorking,
     * requiresApproval, deductsLeaveBalance, visibleToPeers, sortOrder.
     */
    public static final String[][] ATTENDANCE_STATUSES = {
        {"WORKING", "근무", "Working", "#2E7D32", "desk", "true", "false", "false", "true", "10"},
        {"REMOTE", "재택", "Remote", "#1565C0", "home", "true", "false", "false", "true", "20"},
        {"FIELD", "외근", "Field work", "#00838F", "briefcase", "true", "false", "false", "true", "30"},
        {"AWAY", "자리비움", "Away", "#EF6C00", "clock", "false", "false", "false", "true", "40"},
        {"LEAVE", "휴가", "Leave", "#6A1B9A", "palm", "false", "true", "true", "true", "50"},
        {"OFF", "휴무", "Off", "#546E7A", "moon", "false", "false", "false", "true", "60"},
    };

    /** Org units. Columns: code, 이름, name, parent code (empty for a root). */
    public static final String[][] HQ_UNITS = {
        {"EXEC", "대표이사실", "Executive Office", ""},
        {"MGMT", "경영지원본부", "Corporate Support", ""},
        {"FIN", "재무팀", "Finance Team", "MGMT"},
        {"HR", "인사팀", "People Team", "MGMT"},
        {"SALES", "영업본부", "Sales Division", ""},
        {"SALES1", "국내영업팀", "Domestic Sales Team", "SALES"},
        {"SALES2", "해외영업팀", "Overseas Sales Team", "SALES"},
        {"RND", "기술연구소", "R&D Centre", ""},
        {"DEV1", "개발1팀", "Engineering Team 1", "RND"},
        {"DEV2", "개발2팀", "Engineering Team 2", "RND"},
    };

    public static final String[][] SUBSIDIARY_UNITS = {
        {"SMGMT", "관리팀", "Administration Team", ""},
        {"SPROD", "생산본부", "Production Division", ""},
        {"SPROD1", "생산1팀", "Production Team 1", "SPROD"},
    };

    /**
     * The people. Columns: employee number, 이름, romanised name, unit code, rank code,
     * job function code, hired on.
     *
     * <p>Thirty-six at 본사 and six at 자회사. Numbers carry the year of joining, the way a
     * Korean company's do, which is also what makes them stable identifiers for the seed to
     * find its own rows by on a second run.
     */
    public static final String[][] HQ_PEOPLE = {
        {"20180001", "김도현", "Kim Do-hyun", "EXEC", "DAEPYO", "GA", "2018-03-02"},
        {"20180002", "이수민", "Lee Su-min", "EXEC", "DAEPYO", "GA", "2018-03-02"},
        {"20180003", "박준영", "Park Jun-yeong", "EXEC", "DAEPYO", "GA", "2018-03-02"},
        {"20180010", "한지우", "Han Ji-woo", "MGMT", "ISA", "GA", "2018-04-02"},
        {"20190021", "최유진", "Choi Yu-jin", "FIN", "BUJANG", "ACCOUNTING", "2019-01-07"},
        {"20200022", "서지훈", "Seo Ji-hoon", "FIN", "GWAJANG", "ACCOUNTING", "2020-02-03"},
        {"20210023", "윤하늘", "Yoon Ha-neul", "FIN", "DAERI", "ACCOUNTING", "2021-03-02"},
        {"20220024", "강민서", "Kang Min-seo", "FIN", "SAWON", "ACCOUNTING", "2022-01-03"},
        {"20230025", "조은비", "Cho Eun-bi", "FIN", "SAWON", "ACCOUNTING", "2023-03-02"},
        {"20190031", "정하윤", "Jung Ha-yoon", "HR", "BUJANG", "HR", "2019-02-11"},
        {"20210032", "남기훈", "Nam Gi-hoon", "HR", "DAERI", "HR", "2021-07-01"},
        {"20230033", "배수연", "Bae Su-yeon", "HR", "SAWON", "HR", "2023-03-02"},
        {"20180040", "오세훈", "Oh Se-hoon", "SALES", "ISA", "SALES", "2018-05-02"},
        {"20190041", "박지훈", "Park Ji-hoon", "SALES1", "BUJANG", "SALES", "2019-03-04"},
        {"20200042", "임채원", "Lim Chae-won", "SALES1", "GWAJANG", "SALES", "2020-04-01"},
        {"20230043", "김민준", "Kim Min-jun", "SALES1", "DAERI", "SALES", "2023-03-02"},
        {"20240044", "이서준", "Lee Seo-jun", "SALES1", "SAWON", "SALES", "2024-01-02"},
        {"20240045", "한소율", "Han So-yul", "SALES1", "SAWON", "SALES", "2024-07-01"},
        {"20250046", "문태경", "Moon Tae-kyung", "SALES1", "SAWON", "SALES", "2025-01-02"},
        {"20190051", "신동주", "Shin Dong-ju", "SALES2", "BUJANG", "SALES", "2019-06-03"},
        {"20210052", "유가온", "Yoo Ga-on", "SALES2", "DAERI", "SALES", "2021-09-01"},
        {"20240053", "홍서아", "Hong Seo-ah", "SALES2", "SAWON", "SALES", "2024-03-04"},
        {"20250054", "권다인", "Kwon Da-in", "SALES2", "SAWON", "SALES", "2025-03-03"},
        {"20180060", "임태윤", "Lim Tae-yoon", "RND", "ISA", "DEV", "2018-06-01"},
        {"20190061", "황도윤", "Hwang Do-yoon", "DEV1", "BUJANG", "DEV", "2019-04-01"},
        {"20200412", "노은성", "Noh Eun-seong", "DEV1", "CHAJANG", "DEV", "2020-04-12"},
        {"20210063", "심유나", "Sim Yu-na", "DEV1", "GWAJANG", "DEV", "2021-02-01"},
        {"20220064", "곽재민", "Kwak Jae-min", "DEV1", "DAERI", "DEV", "2022-03-02"},
        {"20220065", "오하람", "Oh Ha-ram", "DEV1", "DAERI", "DEV", "2022-09-01"},
        {"20230066", "백승우", "Baek Seung-woo", "DEV1", "SAWON", "DEV", "2023-03-02"},
        {"20240067", "전예린", "Jeon Ye-rin", "DEV1", "SAWON", "DEV", "2024-01-02"},
        {"20250068", "장우진", "Jang Woo-jin", "DEV1", "SAWON", "DEV", "2025-02-03"},
        {"20190071", "노건우", "Noh Geon-woo", "DEV2", "BUJANG", "DEV", "2019-08-01"},
        {"20220072", "하지민", "Ha Ji-min", "DEV2", "DAERI", "DEV", "2022-04-01"},
        {"20240073", "안서윤", "An Seo-yoon", "DEV2", "SAWON", "DEV", "2024-05-02"},
        {"20250074", "표승현", "Pyo Seung-hyun", "DEV2", "SAWON", "DEV", "2025-06-01"},
    };

    public static final String[][] SUBSIDIARY_PEOPLE = {
        {"21000001", "김대호", "Kim Dae-ho", "SMGMT", "DAEPYO", "GA", "2021-06-15"},
        {"21000002", "류지원", "Ryu Ji-won", "SMGMT", "DAEPYO", "ACCOUNTING", "2021-06-15"},
        {"21000003", "도현우", "Do Hyun-woo", "SMGMT", "BUJANG", "GA", "2021-07-01"},
        {"21000004", "명서준", "Myung Seo-jun", "SPROD1", "BUJANG", "DEV", "2021-08-02"},
        {"21000005", "소하은", "So Ha-eun", "SPROD1", "DAERI", "DEV", "2022-03-02"},
        {"21000006", "진태호", "Jin Tae-ho", "SPROD1", "SAWON", "DEV", "2023-03-02"},
    };

    /**
     * Employees who are given a sign-in account. Not everyone has one: an employee is a
     * person on the payroll and an account is a way in, and conflating them is how demo data
     * stops resembling a real installation. These are the people the demo needs to act as.
     */
    public static final String[] ACCOUNT_EMPLOYEE_NUMBERS = {
        "20180001", "20180002", "20180003", // 공동대표 3인
        "20180010", "20180040", // 이사
        "20190021", "20190031", // 재무팀장, 인사팀장
        "20190041", "20230043", "20240044", // 국내영업팀 - 데모 계정 포함
        "20190061", // 개발1팀장
        "21000001", "21000002", "21000003", // 자회사 대표 2인과 관리팀장
    };

    /**
     * The accounts the seed enrols an authenticator for, and prints credentials for.
     *
     * <p>Four, not fourteen: one per screen worth demonstrating — the 부장 with an inbox, the
     * 인사팀 who owns attendance, the 재무팀 who owns the books, and a 대표 who can show what a
     * 공동대표 signature looks like. Every additional set of one-time codes on the console is a
     * set nobody reads.
     */
    public static final String[][] SIGN_IN_AS = {
        {DEMO_EMPLOYEE_NUMBER, "국내영업팀 부장 — 결재할 문서가 기다리고 있습니다"},
        {HR_LEAD_EMPLOYEE_NUMBER, "인사팀 부장 — 근태와 취업규칙을 담당합니다"},
        {FINANCE_LEAD_EMPLOYEE_NUMBER, "재무팀 부장 — 총계정원장을 담당합니다"},
        {MASTER_EMPLOYEE_NUMBER, "대표이사 (공동대표 3인 중 1인, 마스터 계정)"},
    };

    /** The teams whose attendance is recorded for the whole month. */
    public static final String[] FULL_MONTH_ATTENDANCE_UNITS = {"DEV1", "SALES1"};

    // ---------------------------------------------------------------- documents

    public static final String DOC_TYPE_EXPENSE = "EXPENSE_REPORT";
    public static final String DOC_TYPE_PURCHASE = "PURCHASE_REQUEST";
}
