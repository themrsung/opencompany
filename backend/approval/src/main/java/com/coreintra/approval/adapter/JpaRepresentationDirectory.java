package com.coreintra.approval.adapter;

import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.approval.entity.CompanyRepresentationEntity;
import com.coreintra.approval.repository.CompanyRepresentationRepository;
import com.coreintra.approval.service.RepresentationDirectory;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the 대표 representation mode out of {@code company_representation}.
 *
 * <p>The interval match is done here rather than in a WHERE clause on purpose.
 * The rows are half-open — {@code effective_to} is the first date no longer
 * covered — and there are a handful of them per company in its lifetime, so the
 * boundary rule lives in one place, next to the test that pins it. An
 * off-by-one in SQL would show up as a document routed under the wrong mode,
 * months later, with nothing on screen to explain it.
 */
@Component
public class JpaRepresentationDirectory implements RepresentationDirectory {

    private final CompanyRepresentationRepository representations;

    public JpaRepresentationDirectory(CompanyRepresentationRepository representations) {
        this.representations = representations;
    }

    @Override
    @Transactional(readOnly = true)
    public RepresentationMode modeOn(String companyId, LocalDate businessDate) {
        List<CompanyRepresentationEntity> rows =
                representations.findByCompanyIdOrderByEffectiveFromDesc(companyId);
        // Newest first, so the first covering row is the one that took effect
        // last — which is the right answer when an old arrangement was left open
        // instead of being closed on the day its successor began.
        for (CompanyRepresentationEntity row : rows) {
            if (row.coversBusinessDate(businessDate)) {
                return row.toMode();
            }
        }
        throw new IllegalStateException(
                companyId + " 회사의 " + businessDate + " 기준 대표 체제가 등록되어 있지 않습니다. "
                        + "회사 설정에서 각자대표 또는 공동대표를 먼저 등록해 주십시오. (No representation "
                        + "arrangement covers " + businessDate + " for company " + companyId
                        + ". Defaulting to 각자대표 would silently downgrade a joint-representation "
                        + "company to single-signature approval, which is the one failure this "
                        + "area exists to prevent, so the operation is refused instead.)");
    }
}
