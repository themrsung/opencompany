package com.coreintra.app.seed;

import com.coreintra.core.permission.PermissionPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identifiers the seed has created so far, passed from one step to the next.
 *
 * <p>Every id in a CoreIntra installation is minted by the service that creates the row, so
 * the seed cannot choose them in advance and cannot hard-code them. It has to remember what
 * it was given, keyed by the stable business identifiers it does control - a unit's code, an
 * employee's number, an account's username.
 *
 * <p>The lookups throw rather than returning null. A seed step that asks for a unit which was
 * never created has a bug in it, and the failure should name the missing key at the point of
 * the mistake rather than surfacing as a null id in an unrelated service three steps later.
 */
final class SeedWorld {

    private final Map<String, String> companyIds = new LinkedHashMap<String, String>();
    private final Map<String, String> unitIds = new LinkedHashMap<String, String>();
    private final Map<String, String> rankIds = new LinkedHashMap<String, String>();
    private final Map<String, String> jobFunctionIds = new LinkedHashMap<String, String>();
    private final Map<String, String> employeeIds = new LinkedHashMap<String, String>();
    private final Map<String, String> positionIds = new LinkedHashMap<String, String>();
    private final Map<String, String> accountIds = new LinkedHashMap<String, String>();
    private final Map<String, PermissionPrincipal> principals =
            new LinkedHashMap<String, PermissionPrincipal>();
    private final Map<String, String> documentIds = new LinkedHashMap<String, String>();
    private PermissionPrincipal operator;
    private String bookId;

    PermissionPrincipal operator() {
        if (operator == null) {
            throw new IllegalStateException("the operator principal has not been bootstrapped yet");
        }
        return operator;
    }

    void setOperator(PermissionPrincipal value) {
        this.operator = value;
    }

    String bookId() {
        return bookId;
    }

    void setBookId(String value) {
        this.bookId = value;
    }

    void putCompany(String companyCode, String companyId) {
        companyIds.put(companyCode, companyId);
    }

    String companyId(String companyCode) {
        return require(companyIds, companyCode, "company");
    }

    List<String> companyCodes() {
        return new ArrayList<String>(companyIds.keySet());
    }

    void putUnit(String companyCode, String unitCode, String unitId) {
        unitIds.put(scoped(companyCode, unitCode), unitId);
    }

    String unitId(String companyCode, String unitCode) {
        return require(unitIds, scoped(companyCode, unitCode), "org unit");
    }

    void putRank(String companyCode, String rankCode, String rankId) {
        rankIds.put(scoped(companyCode, rankCode), rankId);
    }

    String rankId(String companyCode, String rankCode) {
        return require(rankIds, scoped(companyCode, rankCode), "rank");
    }

    void putJobFunction(String companyCode, String functionCode, String jobFunctionId) {
        jobFunctionIds.put(scoped(companyCode, functionCode), jobFunctionId);
    }

    String jobFunctionId(String companyCode, String functionCode) {
        return require(jobFunctionIds, scoped(companyCode, functionCode), "job function");
    }

    void putEmployee(String employeeNumber, String employeeId) {
        employeeIds.put(employeeNumber, employeeId);
    }

    String employeeId(String employeeNumber) {
        return require(employeeIds, employeeNumber, "employee");
    }

    void putPosition(String employeeNumber, String positionId) {
        positionIds.put(employeeNumber, positionId);
    }

    String positionId(String employeeNumber) {
        return require(positionIds, employeeNumber, "position");
    }

    void putAccount(String employeeNumber, String accountId, PermissionPrincipal principal) {
        accountIds.put(employeeNumber, accountId);
        principals.put(employeeNumber, principal);
    }

    String accountId(String employeeNumber) {
        return require(accountIds, employeeNumber, "account");
    }

    /** The principal for an employee's own account: the seed acts as people, not as itself. */
    PermissionPrincipal principal(String employeeNumber) {
        PermissionPrincipal found = principals.get(employeeNumber);
        if (found == null) {
            throw new IllegalStateException(
                    "no account was seeded for employee " + employeeNumber
                            + "; add the number to DemoCompany.ACCOUNT_EMPLOYEE_NUMBERS");
        }
        return found;
    }

    void putDocument(String label, String documentId) {
        documentIds.put(label, documentId);
    }

    String documentId(String label) {
        return require(documentIds, label, "document");
    }

    int documentCount() {
        return documentIds.size();
    }

    private static String scoped(String companyCode, String code) {
        return companyCode + "/" + code;
    }

    private static String require(Map<String, String> source, String key, String what) {
        String found = source.get(key);
        if (found == null) {
            throw new IllegalStateException("the seed asked for " + what + " \"" + key
                    + "\" before creating it");
        }
        return found;
    }
}
