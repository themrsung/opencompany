package com.coreintra.businesstime;

import java.io.Serializable;
import java.util.Comparator;

/**
 * The one ordering for {@link BusinessInstant}: business date first, then offset.
 *
 * <p>This exists as a named type rather than a lambda so that it can be
 * referenced from review guidance, from persistence configuration, and from the
 * frontend's mirror implementation — and so that a sort site reads as having
 * chosen an ordering rather than inherited one.
 *
 * <p>Two orderings are available for these values and they genuinely disagree:
 *
 * <ul>
 *   <li>this one — {@code 2026-08-30T26:01:00.000} &lt; {@code 2026-08-31T-03:22:00.000},
 *       because the 30th precedes the 31st;</li>
 *   <li>absolute wall-clock — the reverse, because 26:01 on the 30th is
 *       02:01 on the 31st, which is after 20:38 on the 30th.</li>
 * </ul>
 *
 * <p>Business ordering is the correct one for approvals, attendance and
 * journals. Absolute ordering is correct only for range scans. Never sort wire
 * strings lexically: {@code '-'} sorts below every digit, producing a third and
 * always-wrong order.
 */
public final class BusinessInstantComparator implements Comparator<BusinessInstant>, Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public int compare(BusinessInstant left, BusinessInstant right) {
        if (left == null || right == null) {
            throw new NullPointerException("BusinessInstant comparison against null");
        }
        int byDate = left.businessDate().compareTo(right.businessDate());
        if (byDate != 0) {
            return byDate;
        }
        return Integer.compare(left.offsetSeconds(), right.offsetSeconds());
    }
}
