package com.coreintra.approval.domain;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * Who a template step points at, before it is resolved to actual people.
 *
 * <p>Steps name roles, not persons: "the 부장 of the drafter's org unit",
 * "anyone with {@code finance.expense:approve} in company X", "the 대표". A
 * template that named individuals would have to be rewritten every time
 * somebody moved, and would route documents to people who left.
 *
 * <p>Resolution happens once, at submission, and the result is snapshotted onto
 * the document (see {@link ApprovalStep}), so a reorganisation cannot corrupt an
 * in-flight approval.
 *
 * <h2>The grammar</h2>
 *
 * <pre>
 *   rank:부장@DRAFTER_UNIT          the 부장 of the drafter's own unit
 *   rank:부장@DRAFTER_UNIT_PARENT   the 부장 of the unit above the drafter's
 *   rank:이사@COMPANY               any 이사 in the drafter's company
 *   permission:finance.expense:approve@COMPANY
 *   representative@COMPANY          the designated 대표이사
 *   jobFunction:회계@COMPANY
 *   account:acc-123                 a specific account, for exceptional lines
 * </pre>
 */
public final class RoleExpression implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What the left-hand side selects on. */
    public enum Selector {
        RANK,
        JOB_FUNCTION,
        PERMISSION,
        /** The company's designated representatives, under its representation mode. */
        REPRESENTATIVE,
        /** A named account. Escape hatch; use sparingly, it re-introduces the drift. */
        ACCOUNT
    }

    /** Where to look, relative to the drafter. */
    public enum Domain {
        DRAFTER_UNIT,
        DRAFTER_UNIT_PARENT,
        DRAFTER_UNIT_SUBTREE,
        COMPANY,
        /** Every company in the installation. Rare; used for group-level 이사회. */
        ALL
    }

    private final Selector selector;
    private final String value;
    private final Domain domain;

    private RoleExpression(Selector selector, String value, Domain domain) {
        this.selector = selector;
        this.value = value;
        this.domain = domain;
    }

    public static RoleExpression rank(String rankCode, Domain domain) {
        return new RoleExpression(Selector.RANK, rankCode, domain);
    }

    public static RoleExpression jobFunction(String functionCode, Domain domain) {
        return new RoleExpression(Selector.JOB_FUNCTION, functionCode, domain);
    }

    public static RoleExpression permission(String permissionKey, Domain domain) {
        return new RoleExpression(Selector.PERMISSION, permissionKey, domain);
    }

    public static RoleExpression representative() {
        return new RoleExpression(Selector.REPRESENTATIVE, null, Domain.COMPANY);
    }

    public static RoleExpression account(String accountId) {
        return new RoleExpression(Selector.ACCOUNT, accountId, Domain.ALL);
    }

    /**
     * @throws IllegalArgumentException on an unparseable expression, quoting it
     */
    public static RoleExpression parse(String text) {
        if (Texts.isBlank(text)) {
            throw new IllegalArgumentException("role expression is blank");
        }
        String trimmed = Texts.strip(text);

        int at = trimmed.lastIndexOf('@');
        String left = at < 0 ? trimmed : trimmed.substring(0, at);
        Domain domain = at < 0 ? Domain.COMPANY : parseDomain(trimmed, trimmed.substring(at + 1));

        if ("representative".equals(left)) {
            return new RoleExpression(Selector.REPRESENTATIVE, null, domain);
        }
        int colon = left.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "cannot parse role expression \"" + text + "\": expected "
                            + "rank:<code>@<domain>, jobFunction:<code>@<domain>, "
                            + "permission:<resource>:<action>@<domain>, representative@<domain>, "
                            + "or account:<id>");
        }
        String head = left.substring(0, colon);
        String tail = left.substring(colon + 1);

        if ("rank".equals(head)) {
            return new RoleExpression(Selector.RANK, tail, domain);
        }
        if ("jobFunction".equals(head)) {
            return new RoleExpression(Selector.JOB_FUNCTION, tail, domain);
        }
        if ("permission".equals(head)) {
            return new RoleExpression(Selector.PERMISSION, tail, domain);
        }
        if ("account".equals(head)) {
            return new RoleExpression(Selector.ACCOUNT, tail, Domain.ALL);
        }
        throw new IllegalArgumentException(
                "cannot parse role expression \"" + text + "\": unknown selector \"" + head + "\"");
    }

    private static Domain parseDomain(String whole, String text) {
        try {
            return Domain.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "cannot parse role expression \"" + whole + "\": unknown domain \"" + text
                            + "\". Expected one of DRAFTER_UNIT, DRAFTER_UNIT_PARENT, "
                            + "DRAFTER_UNIT_SUBTREE, COMPANY, ALL.");
        }
    }

    public Selector selector() {
        return selector;
    }

    /** Rank code, function code, permission key or account id. Null for REPRESENTATIVE. */
    public String value() {
        return value;
    }

    public Domain domain() {
        return domain;
    }

    /** The canonical text form, round-trippable through {@link #parse}. */
    @Override
    public String toString() {
        switch (selector) {
            case REPRESENTATIVE:
                return "representative@" + domain;
            case RANK:
                return "rank:" + value + "@" + domain;
            case JOB_FUNCTION:
                return "jobFunction:" + value + "@" + domain;
            case PERMISSION:
                return "permission:" + value + "@" + domain;
            case ACCOUNT:
            default:
                return "account:" + value;
        }
    }
}
