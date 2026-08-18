package com.coreintra.app.api.support;

import com.coreintra.compat.Immutables;
import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Says what a ticked capability actually lets a support engineer see, in words.
 *
 * <p>§8 is explicit about this and it is the part most likely to be skipped:
 * the issuance screen and the live banner must state what the capabilities
 * allow <b>in end-user terms</b> — "read every employee's salary history", not
 * "{@code hr.compensation:read}". A 대표이사 approving a support session cannot
 * consent to a permission string, and a system that asks them to has obtained
 * a signature rather than a decision.
 *
 * <p>An unknown capability is <b>not</b> rendered as its identifier and quietly
 * passed through. It comes back as an explicit warning that this capability has
 * no plain-language description, because the alternative is a screen that looks
 * complete while showing something nobody in the room understood. Adding a
 * capability therefore means adding its wording here, and the test that pins
 * the never-grantable list also pins that.
 */
public final class SupportCapabilityWording {

    private static final Map<String, String[]> WORDING = wording();

    private SupportCapabilityWording() {
    }

    /** @return one sentence per capability, Korean first, then English in brackets */
    public static List<String> describe(TemporaryMasterGrantRow grant) {
        List<String> described = new ArrayList<String>();
        for (String capability : grant.capabilities()) {
            String[] both = WORDING.get(capability);
            if (both == null) {
                described.add("설명이 등록되지 않은 권한입니다: " + capability
                        + " (no plain-language description is registered for this capability)");
            } else {
                described.add(both[0] + " (" + both[1] + ")");
            }
        }
        return Immutables.copyOf(described);
    }

    /** Every capability that can be ticked, in the order the issuance screen shows them. */
    public static List<String> keys() {
        return Immutables.copyOf(WORDING.keySet());
    }

    /** @return the ko + en sentence for one capability, or null if none is registered */
    public static String describe(String capability) {
        String[] both = WORDING.get(capability);
        return both == null ? null : both[0] + " (" + both[1] + ")";
    }

    /** True when every capability on the grant has wording. Checked before issuance. */
    public static boolean fullyDescribed(Iterable<String> capabilities) {
        for (String capability : capabilities) {
            if (!WORDING.containsKey(capability)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String[]> wording() {
        Map<String, String[]> map = new LinkedHashMap<String, String[]>();

        map.put("hr.employee:read", new String[] {
            "전 직원의 인사 기본 정보를 조회합니다",
            "read every employee's personnel record"});
        map.put("hr.employee:export", new String[] {
            "전 직원 명부를 파일로 내려받습니다",
            "download the entire employee list as a file"});
        map.put("hr.compensation:read", new String[] {
            "전 직원의 급여 이력을 조회합니다",
            "read every employee's salary history"});
        map.put("attendance.record:read", new String[] {
            "전 직원의 출퇴근 기록을 조회합니다",
            "read every employee's attendance record"});
        map.put("attendance.leave:read", new String[] {
            "전 직원의 휴가 사용 내역을 조회합니다",
            "read every employee's leave history"});
        map.put("approval.document:read", new String[] {
            "결재 문서의 본문과 결재 이력을 조회합니다",
            "read approval documents and their approval trails"});
        map.put("documents.document:read", new String[] {
            "저장된 모든 문서를 열어봅니다",
            "open every stored document"});
        map.put("documents.template:read", new String[] {
            "서식과 서식 설정을 조회합니다",
            "read templates and their configuration"});
        map.put("documents.template:update", new String[] {
            "서식을 수정합니다. 이후 작성되는 문서의 모양이 바뀝니다",
            "change templates, which changes how future documents look"});
        map.put("accounting.entry:read", new String[] {
            "전표와 분개 내역을 조회합니다",
            "read journal entries and their postings"});
        map.put("accounting.report:read", new String[] {
            "재무제표와 회계 보고서를 조회합니다",
            "read the financial statements and accounting reports"});
        map.put("admin.setting:read", new String[] {
            "시스템 설정값을 조회합니다",
            "read the installation's settings"});
        map.put("admin.setting:update", new String[] {
            "시스템 설정값을 변경합니다",
            "change the installation's settings"});
        map.put("admin.font:manage", new String[] {
            "글꼴을 설치하거나 제거합니다. 문서 출력 모양에 영향을 줍니다",
            "install and remove fonts, which affects how documents print"});
        map.put("admin.module:read", new String[] {
            "설치된 모듈 목록을 조회합니다",
            "list the installed modules"});
        map.put("admin.session:read", new String[] {
            "다른 사용자의 로그인 세션 목록을 조회합니다",
            "list other users' sign-in sessions"});
        map.put("admin.session:revoke", new String[] {
            "다른 사용자를 강제로 로그아웃시킵니다",
            "sign other users out"});
        map.put("admin.audit:read", new String[] {
            "감사 로그를 조회합니다",
            "read the audit log"});

        return Immutables.mapCopyOf(map);
    }
}
