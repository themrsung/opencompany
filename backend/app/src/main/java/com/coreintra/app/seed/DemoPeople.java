package com.coreintra.app.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading of the people table in {@link DemoCompany}.
 *
 * <p>Usernames and e-mail addresses are derived from the romanised name rather than written out
 * a third time, because three parallel columns that must agree are three chances to disagree.
 * The derivation is checked for collisions when the class loads: two people who would share a
 * username is a fault in the demo data, and it should stop the seed at the point the table is
 * read rather than surfacing as a unique-constraint violation half way through the run.
 */
final class DemoPeople {

    /** Column indices into the {@code String[]} rows of {@link DemoCompany#HQ_PEOPLE}. */
    static final int NUMBER = 0;
    static final int NAME_KO = 1;
    static final int NAME_EN = 2;
    static final int UNIT = 3;
    static final int RANK = 4;
    static final int FUNCTION = 5;
    static final int HIRED_ON = 6;

    private static final Map<String, String[]> BY_NUMBER = new LinkedHashMap<String, String[]>();
    private static final Map<String, String> COMPANY_OF = new LinkedHashMap<String, String>();
    private static final Map<String, String> USERNAMES = new LinkedHashMap<String, String>();

    static {
        index(DemoCompany.HQ_CODE, DemoCompany.HQ_PEOPLE);
        index(DemoCompany.SUBSIDIARY_CODE, DemoCompany.SUBSIDIARY_PEOPLE);
    }

    private DemoPeople() {
    }

    private static void index(String companyCode, String[][] people) {
        for (String[] person : people) {
            String number = person[NUMBER];
            if (BY_NUMBER.put(number, person) != null) {
                throw new IllegalStateException("두 사람이 같은 사번을 사용합니다: " + number);
            }
            COMPANY_OF.put(number, companyCode);
            String username = derivedUsername(person[NAME_EN]);
            String clash = USERNAMES.put(username, number);
            if (clash != null) {
                throw new IllegalStateException("계정 아이디가 겹칩니다: " + username + " ("
                        + clash + ", " + number + ")");
            }
        }
    }

    static String[] byNumber(String employeeNumber) {
        String[] found = BY_NUMBER.get(employeeNumber);
        if (found == null) {
            throw new IllegalStateException("데모 인사 자료에 없는 사번입니다: " + employeeNumber);
        }
        return found;
    }

    static String companyCodeOf(String employeeNumber) {
        return COMPANY_OF.get(employeeNumber);
    }

    static List<String[]> of(String companyCode) {
        List<String[]> people = new ArrayList<String[]>();
        for (Map.Entry<String, String[]> entry : BY_NUMBER.entrySet()) {
            if (companyCode.equals(COMPANY_OF.get(entry.getKey()))) {
                people.add(entry.getValue());
            }
        }
        return people;
    }

    static int total() {
        return BY_NUMBER.size();
    }

    static String username(String[] person) {
        return derivedUsername(person[NAME_EN]);
    }

    static String email(String[] person) {
        return derivedUsername(person[NAME_EN]) + "@" + DemoCompany.EMAIL_DOMAIN;
    }

    static LocalDate hiredOn(String[] person) {
        return LocalDate.parse(person[HIRED_ON]);
    }

    /** "Park Ji-hoon" becomes "park.jihoon" — surname first, as the company's own directory has it. */
    private static String derivedUsername(String romanised) {
        String[] parts = romanised.trim().split("\\s+");
        StringBuilder given = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            given.append(parts[i].replace("-", ""));
        }
        return (parts[0] + "." + given).toLowerCase(Locale.ROOT);
    }
}
