package com.coreintra.app.seed;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Company;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.JobFunction;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.Position;
import com.coreintra.core.org.Rank;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.service.CompanyService;
import com.coreintra.core.service.EmployeeService;
import com.coreintra.core.service.JobFunctionService;
import com.coreintra.core.service.OrgUnitService;
import com.coreintra.core.service.PermissionGrantService;
import com.coreintra.core.service.PositionService;
import com.coreintra.core.service.RankService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The organisation: two legal entities, their ladders, their units and their people.
 *
 * <p>Everything here goes through the same services a person clicking around the org admin
 * screens would drive, as the same operator principal, with the same permission checks. That
 * is the point of doing it this way rather than with SQL: if {@code PositionService} grows a
 * rule tomorrow about who may open a seat in a unit, this seed either satisfies it or fails
 * loudly, and either answer is worth having.
 */
@Component
@Profile(DemoSeedRunner.SEED_PROFILE)
class OrgSeed {

    private final CompanyService companies;
    private final OrgUnitService units;
    private final RankService ranks;
    private final JobFunctionService jobFunctions;
    private final EmployeeService employees;
    private final PositionService positions;
    private final PermissionGrantService permissionGrants;

    OrgSeed(CompanyService companies, OrgUnitService units, RankService ranks,
            JobFunctionService jobFunctions, EmployeeService employees, PositionService positions,
            PermissionGrantService permissionGrants) {
        this.companies = companies;
        this.units = units;
        this.ranks = ranks;
        this.jobFunctions = jobFunctions;
        this.employees = employees;
        this.positions = positions;
        this.permissionGrants = permissionGrants;
    }

    /**
     * 본사 and 자회사, with the parent relationship set through the service that owns it. The
     * subsidiary is created naming its parent rather than being reparented afterwards, because
     * a subsidiary that exists parentless even for one transaction is a subsidiary that could be
     * left that way by a failure half way through.
     */
    void companies(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        LocalDate on = DemoCompany.TODAY;

        Company hq = companies.create(operator, DemoCompany.HQ_CODE, DemoCompany.HQ_NAME_KO,
                Company.CompanyKind.HEAD_OFFICE, null, on);
        companies.rename(operator, hq.id(), DemoCompany.HQ_NAME_KO, DemoCompany.HQ_NAME_EN, on);
        companies.updateRegistration(operator, hq.id(), DemoCompany.HQ_REGISTRATION_NUMBER,
                DemoCompany.BASE_CURRENCY_CODE, DemoCompany.HQ_ESTABLISHED_ON, on);
        world.putCompany(DemoCompany.HQ_CODE, hq.id());

        Company subsidiary = companies.create(operator, DemoCompany.SUBSIDIARY_CODE,
                DemoCompany.SUBSIDIARY_NAME_KO, Company.CompanyKind.SUBSIDIARY, hq.id(), on);
        companies.rename(operator, subsidiary.id(), DemoCompany.SUBSIDIARY_NAME_KO,
                DemoCompany.SUBSIDIARY_NAME_EN, on);
        companies.updateRegistration(operator, subsidiary.id(),
                DemoCompany.SUBSIDIARY_REGISTRATION_NUMBER, DemoCompany.BASE_CURRENCY_CODE,
                DemoCompany.SUBSIDIARY_ESTABLISHED_ON, on);
        world.putCompany(DemoCompany.SUBSIDIARY_CODE, subsidiary.id());
    }

    /**
     * The 직급 ladder and the 직무 set, for both entities.
     *
     * <p>{@code V6__seed_defaults.sql} carries exactly these rows, but it inserts them for the
     * companies that exist when the migration runs — on a fresh installation, none. Any company
     * created afterwards starts with an empty catalogue, so the seed lays the same ladder with
     * the same codes and labels rather than inventing a parallel one.
     */
    void catalogues(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        LocalDate on = DemoCompany.TODAY;
        for (String companyCode : world.companyCodes()) {
            String companyId = world.companyId(companyCode);
            for (String[] row : DemoCompany.RANKS) {
                Rank rank = ranks.create(operator, companyId, row[0], row[1], row[2],
                        Integer.parseInt(row[3]), Boolean.parseBoolean(row[4]), on);
                world.putRank(companyCode, row[0], rank.id());
            }
            for (String[] row : DemoCompany.JOB_FUNCTIONS) {
                JobFunction function = jobFunctions.create(operator, companyId, row[0], row[1],
                        row[2], on);
                world.putJobFunction(companyCode, row[0], function.id());
            }
        }
    }

    /** The org tree. Parents first, which the table is ordered to guarantee. */
    void units(SeedWorld world) {
        units(world, DemoCompany.HQ_CODE, DemoCompany.HQ_UNITS);
        units(world, DemoCompany.SUBSIDIARY_CODE, DemoCompany.SUBSIDIARY_UNITS);
    }

    private void units(SeedWorld world, String companyCode, String[][] table) {
        PermissionPrincipal operator = world.operator();
        LocalDate on = DemoCompany.TODAY;
        String companyId = world.companyId(companyCode);
        int sortOrder = 10;
        for (String[] row : table) {
            String parentId = Texts.hasText(row[3]) ? world.unitId(companyCode, row[3]) : null;
            OrgUnit unit = units.create(operator, companyId, parentId, row[0], row[1], on);
            units.rename(operator, unit.id(), row[1], row[2], on);
            units.setSortOrder(operator, unit.id(), sortOrder, on);
            world.putUnit(companyCode, row[0], unit.id());
            sortOrder += 10;
        }
    }

    /**
     * The people, and the seat each of them holds.
     *
     * <p>Positions are opened with the employee's own hire date as {@code effectiveFrom}, not
     * with today's, so that the org chart is answerable as of any date in the demo's history —
     * which is what the permission evaluator does when it resolves a past-dated document.
     * 김민준 then gets a second seat from {@link DemoCompany#PROMOTION_DATE} through the
     * reassignment path, so the demo contains someone whose authority genuinely differs
     * depending on which document you are looking at.
     */
    void people(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        LocalDate on = DemoCompany.TODAY;
        for (String companyCode : world.companyCodes()) {
            String companyId = world.companyId(companyCode);
            for (String[] person : DemoPeople.of(companyCode)) {
                Employee employee = employees.create(operator, companyId,
                        person[DemoPeople.NUMBER], person[DemoPeople.NAME_KO],
                        person[DemoPeople.NAME_EN], DemoPeople.email(person),
                        DemoPeople.hiredOn(person), on);
                world.putEmployee(person[DemoPeople.NUMBER], employee.id());

                Position position = positions.assign(operator, employee.id(),
                        world.unitId(companyCode, person[DemoPeople.UNIT]),
                        world.rankId(companyCode, person[DemoPeople.RANK]),
                        Immutables.listOf(world.jobFunctionId(companyCode,
                                person[DemoPeople.FUNCTION])),
                        DemoPeople.hiredOn(person), true, on);
                world.putPosition(person[DemoPeople.NUMBER], position.id());
            }
        }
    }

    /** 김민준, 대리 → 과장, effective the first of August. */
    void promotion(SeedWorld world) {
        String[] person = DemoPeople.byNumber(DemoCompany.PROMOTED_EMPLOYEE_NUMBER);
        String companyCode = DemoPeople.companyCodeOf(DemoCompany.PROMOTED_EMPLOYEE_NUMBER);
        Position promoted = positions.reassign(world.operator(),
                world.positionId(DemoCompany.PROMOTED_EMPLOYEE_NUMBER),
                world.unitId(companyCode, person[DemoPeople.UNIT]),
                world.rankId(companyCode, "GWAJANG"),
                Immutables.listOf(world.jobFunctionId(companyCode, person[DemoPeople.FUNCTION])),
                DemoCompany.PROMOTION_DATE, DemoCompany.TODAY);
        world.putPosition(DemoCompany.PROMOTED_EMPLOYEE_NUMBER, promoted.id());
    }

    /**
     * The grant book.
     *
     * <p>Attached to 직급 and 직무 rather than to people, which is how a real installation is
     * administered and what makes the explainer worth opening: "박지훈 may read this because he
     * is a 부장 in 국내영업팀", not "because somebody ticked a box on his account". The one deny
     * is deliberate — an installation with no deny in it never shows what the explainer looks
     * like when one bites.
     */
    void grants(SeedWorld world) {
        PermissionPrincipal operator = world.operator();
        for (String companyCode : world.companyCodes()) {
            for (String[] rank : DemoCompany.RANKS) {
                String rankId = world.rankId(companyCode, rank[0]);
                grant(operator, GrantSource.RANK, rankId, SeedPermissions.EVERY_RANK_SELF,
                        PermissionScope.SELF, true, "본인 근태는 본인이 기록합니다.");
                grant(operator, GrantSource.RANK, rankId, SeedPermissions.EVERY_RANK_SUBTREE,
                        PermissionScope.ORG_UNIT_SUBTREE, true,
                        "소속 조직의 문서를 기안하고 결재합니다.");
            }
            grant(operator, GrantSource.RANK, world.rankId(companyCode, "BUJANG"),
                    SeedPermissions.BUJANG_SUBTREE, PermissionScope.ORG_UNIT_SUBTREE, true,
                    "부장은 소속 조직의 인사와 근태를 확인합니다.");
            grant(operator, GrantSource.RANK, world.rankId(companyCode, "ISA"),
                    SeedPermissions.ISA_SUBTREE, PermissionScope.ORG_UNIT_SUBTREE, true,
                    "이사는 담당 본부의 인사와 근태를 확인합니다.");
            grant(operator, GrantSource.RANK, world.rankId(companyCode, "GWAJANG"),
                    SeedPermissions.GWAJANG_COMPANY, PermissionScope.COMPANY, true,
                    "과장 이상은 회계 보고서를 열람합니다.");
            grant(operator, GrantSource.RANK, world.rankId(companyCode, "DAEPYO"),
                    SeedPermissions.DAEPYO_COMPANY, PermissionScope.COMPANY, true,
                    "대표이사는 회사 전체를 열람하고 결재합니다.");
            grant(operator, GrantSource.JOB_FUNCTION, world.jobFunctionId(companyCode, "HR"),
                    SeedPermissions.HR_FUNCTION_COMPANY, PermissionScope.COMPANY, true,
                    "인사 직무는 인사·근태·취업규칙을 담당합니다.");
            grant(operator, GrantSource.JOB_FUNCTION,
                    world.jobFunctionId(companyCode, "ACCOUNTING"),
                    SeedPermissions.ACCOUNTING_FUNCTION_COMPANY, PermissionScope.COMPANY, true,
                    "회계 직무는 장부를 담당합니다.");
        }

        grant(operator, GrantSource.ORG_UNIT, world.unitId(DemoCompany.HQ_CODE, "SALES2"),
                SeedPermissions.OVERSEAS_SALES_DENY, PermissionScope.COMPANY, false,
                "해외영업팀은 원가가 드러나는 회계 보고서를 열람하지 않습니다.");
    }

    private void grant(PermissionPrincipal operator, GrantSource source, String sourceId,
            String[] keys, PermissionScope scope, boolean allow, String reason) {
        List<PermissionKey> parsed = SeedPermissions.keys(keys);
        for (PermissionKey key : parsed) {
            permissionGrants.grant(operator, source, sourceId, key, scope, allow, reason,
                    DemoCompany.TODAY);
        }
    }
}
