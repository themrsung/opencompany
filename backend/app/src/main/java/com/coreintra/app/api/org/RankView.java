package com.coreintra.app.api.org;

import com.coreintra.core.org.Rank;
import java.util.function.Function;

/**
 * A rung of the 직급 ladder on the wire.
 *
 * <p>Nothing about Korean corporate titles is hardcoded anywhere in this
 * system (§3): the labels and the ordering are both client data, and 사원 is a
 * seeded row rather than a constant. This DTO therefore carries the seniority
 * number, because a client that wants to say "more senior than 과장" has to
 * compare something, and it must not compare labels.
 */
public class RankView {

    public static final Function<Rank, RankView> MAPPER = new Function<Rank, RankView>() {
        @Override
        public RankView apply(Rank rank) {
            return from(rank);
        }
    };

    /** Seniority, most senior first, with the id breaking ties. */
    public static final Pages.Keys<Rank> KEYS = new Pages.Keys<Rank>() {
        @Override
        public String sortKey(Rank rank) {
            // Negated so the ladder comes back top-down, which is how every
            // 조직도 in the country is drawn.
            return Pages.number(-rank.seniority());
        }

        @Override
        public String id(Rank rank) {
            return rank.id();
        }
    };

    private final String id;
    private final String companyId;
    private final String code;
    private final String labelKo;
    private final String labelEn;
    private final int seniority;
    private final boolean representative;
    private final boolean active;

    RankView(String id, String companyId, String code, String labelKo, String labelEn,
            int seniority, boolean representative, boolean active) {
        this.id = id;
        this.companyId = companyId;
        this.code = code;
        this.labelKo = labelKo;
        this.labelEn = labelEn;
        this.seniority = seniority;
        this.representative = representative;
        this.active = active;
    }

    public static RankView from(Rank rank) {
        return new RankView(rank.id(), rank.companyId(), rank.code(), rank.labelKo(),
                rank.labelEn(), rank.seniority(), rank.isRepresentative(), rank.isActive());
    }

    public static String tagOf(Rank rank) {
        return OrgVersions.tag(rank.id(), rank.code(), rank.labelKo(), rank.labelEn(),
                Integer.valueOf(rank.seniority()), Boolean.valueOf(rank.isRepresentative()),
                Boolean.valueOf(rank.isActive()));
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getCode() {
        return code;
    }

    public String getLabelKo() {
        return labelKo;
    }

    public String getLabelEn() {
        return labelEn;
    }

    /** Higher is more senior. Client-defined; the gaps are theirs to choose. */
    public int getSeniority() {
        return seniority;
    }

    /** 대표이사. Drives the representation modes the approval module applies. */
    public boolean isRepresentative() {
        return representative;
    }

    public boolean isActive() {
        return active;
    }
}
