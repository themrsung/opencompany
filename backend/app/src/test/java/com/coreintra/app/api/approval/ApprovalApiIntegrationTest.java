package com.coreintra.app.api.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coreintra.app.api.approval.support.ApiTestWorld;
import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.approval.domain.RepresentationMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The 결재 REST surface, over HTTP, against real PostgreSQL.
 *
 * <p>What these cover that the service tests cannot: that the routes exist and
 * are reachable with a real session cookie, that the wire shapes carry business
 * instants and amounts as strings without mangling them, that the domain's
 * refusals arrive as usable problem+json rather than 500s, and that the inbox —
 * the screen §12 says decides whether people like this product — answers in one
 * request with counts that match its own lists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestWorld.class)
class ApprovalApiIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApiTestWorld world;
    private Cookie drafter;
    private Cookie approver;
    private Cookie firstRep;
    private Cookie secondRep;
    private Cookie observer;

    @BeforeEach
    void seed() {
        world.reset();
        world.registerExpenseTemplate();

        drafter = world.sessionFor(world.drafterAccountId);
        approver = world.sessionFor(world.approverAccountId);
        firstRep = world.sessionFor(world.firstRepAccountId);
        secondRep = world.sessionFor(world.secondRepAccountId);
        observer = world.sessionFor(world.observerAccountId);
    }

    // ------------------------------------------------------------------
    // The inbox
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the 결재함")
    class Inbox {

        @Test
        @DisplayName("puts each document in exactly one bucket and reports counts that match "
                + "the rows it returned")
        void bucketsAndCountsAgree() throws Exception {
            String documentId = draftAndSubmit("8월 출장비", "120000");

            JsonNode approverInbox = inboxOf(approver, world.approverAccountId);
            assertThat(ids(approverInbox, "awaitingMe"))
                    .as("박부장 is the pending approver, so it is waiting on them")
                    .containsExactly(documentId);
            assertThat(ids(approverInbox, "draftedByMe")).isEmpty();
            assertThat(ids(approverInbox, "copiedToMe"))
                    .as("박부장 approves it; they are not merely copied on it")
                    .isEmpty();

            JsonNode drafterInbox = inboxOf(drafter, world.drafterAccountId);
            assertThat(ids(drafterInbox, "draftedByMe")).containsExactly(documentId);
            assertThat(ids(drafterInbox, "awaitingMe"))
                    .as("the 기안 step needs no action, so the drafter is not waiting on "
                            + "themselves")
                    .isEmpty();
            assertThat(ids(drafterInbox, "copiedToMe")).isEmpty();

            JsonNode observerInbox = inboxOf(observer, world.observerAccountId);
            assertThat(ids(observerInbox, "copiedToMe")).containsExactly(documentId);
            assertThat(ids(observerInbox, "awaitingMe"))
                    .as("참조 requires no action and the inbox must not imply one")
                    .isEmpty();
            assertThat(ids(observerInbox, "draftedByMe")).isEmpty();

            // The badge is drawn before the lists are, so it has to be the same
            // number the lists would produce.
            assertThat(approverInbox.path("counts").path("awaiting").asInt()).isEqualTo(1);
            assertThat(drafterInbox.path("counts").path("draftedByMe").asInt()).isEqualTo(1);
            assertThat(drafterInbox.path("counts").path("inProgressByMe").asInt()).isEqualTo(1);
            assertThat(drafterInbox.path("counts").path("returnedToMe").asInt()).isZero();
            assertThat(observerInbox.path("counts").path("copiedToMe").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("moves a returned document out of the approver's list and into the "
                + "drafter's returned count")
        void returnedCountFollowsTheDocument() throws Exception {
            String documentId = draftAndSubmit("과다 청구 건", "90000");
            returnDocument(documentId, "영수증이 누락되었습니다. 카드 전표를 첨부해 다시 올려 주십시오.");

            JsonNode drafterInbox = inboxOf(drafter, world.drafterAccountId);
            assertThat(drafterInbox.path("counts").path("returnedToMe").asInt()).isEqualTo(1);
            assertThat(drafterInbox.path("counts").path("inProgressByMe").asInt()).isZero();
            assertThat(ids(inboxOf(approver, world.approverAccountId), "awaitingMe"))
                    .as("it is the drafter's problem now, not the approver's")
                    .isEmpty();
            assertThat(ids(drafterInbox, "draftedByMe")).containsExactly(documentId);
        }
    }

    // ------------------------------------------------------------------
    // 반려
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("반려")
    class Returning {

        @Test
        @DisplayName("is refused without a reason, and the message says what to write")
        void reasonIsMandatory() throws Exception {
            String documentId = draftAndSubmit("사유 없는 반려 시험", "10000");
            String stepId = pendingStepId(documentId, approver);

            MvcResult result = mockMvc.perform(post(
                    "/api/v1/approvals/{id}/steps/{step}/return", documentId, stepId)
                    .cookie(approver)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T14:00:00.000\",\"reason\":\"   \"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode problem = read(result);
            assertThat(problem.path("code").asText()).isEqualTo("validation_failed");

            JsonNode violation = problem.path("violations").get(0);
            assertThat(violation.path("field").asText()).isEqualTo("reason");
            assertThat(violation.path("message").asText())
                    .as("the message has to tell the approver what to do, not merely that "
                            + "something was missing")
                    .contains("사유")
                    .contains("what the drafter should change");

            // And nothing was stored: an empty reason is worse than no action.
            assertThat(stateOf(documentId, approver)).isEqualTo("IN_PROGRESS");
            assertThat(trailSize(documentId, approver)).isZero();
        }

        @Test
        @DisplayName("with a reason returns the document and records the reason on the trail")
        void reasonIsKept() throws Exception {
            String documentId = draftAndSubmit("영수증 누락", "10000");
            String reason = "영수증이 누락되었습니다. 카드 전표를 첨부해 다시 올려 주십시오.";
            returnDocument(documentId, reason);

            JsonNode detail = readDocument(documentId, drafter);
            assertThat(detail.path("document").path("state").asText()).isEqualTo("RETURNED");
            JsonNode entry = detail.path("trail").get(0);
            assertThat(entry.path("action").asText()).isEqualTo("RETURN");
            assertThat(entry.path("comment").asText()).isEqualTo(reason);
            assertThat(entry.path("actorAccountId").asText())
                    .isEqualTo(world.approverAccountId);
        }
    }

    // ------------------------------------------------------------------
    // 공동대표
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("공동대표 (joint representation)")
    class JointRepresentation {

        @Test
        @DisplayName("cannot be satisfied by one representative through the API, and partial "
                + "approval is a distinct visible state")
        void oneRepresentativeIsNotAQuorum() throws Exception {
            world.useRepresentation(RepresentationMode.jointAll(2));
            // Over the threshold, so the template adds the 대표 step.
            String documentId = draftAndSubmit("사옥 보증금", "50000000");

            approveStep(documentId, pendingStepId(documentId, approver), approver);

            JsonNode afterFirstRep = approveStep(
                    documentId, pendingStepId(documentId, firstRep), firstRep);

            assertThat(afterFirstRep.path("document").path("state").asText())
                    .as("one 대표 signing a 공동대표 document must not finish it")
                    .isEqualTo("PARTIALLY_APPROVED");
            assertThat(afterFirstRep.path("representation").path("kind").asText())
                    .isEqualTo("JOINT");
            assertThat(afterFirstRep.path("representation").path("requiredApprovals").asInt())
                    .isEqualTo(2);

            JsonNode repStep = stepWithState(afterFirstRep, "PENDING");
            assertThat(repStep.path("requiredApprovals").asInt()).isEqualTo(2);
            assertThat(repStep.path("approvalsGiven").asInt()).isEqualTo(1);
            assertThat(repStep.path("remainingApprovals").asInt())
                    .as("the screen has to be able to say 'one more signature', not just "
                            + "'still waiting'")
                    .isEqualTo(1);

            // The same 대표 signing again does not complete it either.
            MvcResult twice = mockMvc.perform(post(
                    "/api/v1/approvals/{id}/steps/{step}/approve",
                    documentId, repStep.path("id").asText())
                    .cookie(firstRep)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T16:00:00.000\"}"))
                    .andReturn();
            assertThat(twice.getResponse().getStatus())
                    .as("a second signature from the same person is not a second signature")
                    .isEqualTo(409);
            assertThat(stateOf(documentId, drafter)).isEqualTo("PARTIALLY_APPROVED");

            // The second 대표 is what finishes it.
            JsonNode afterSecondRep = approveStep(
                    documentId, repStep.path("id").asText(), secondRep);
            assertThat(afterSecondRep.path("document").path("state").asText())
                    .isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("각자대표 finishes on one signature, which is the difference the mode "
                + "exists to express")
        void severalRepresentationNeedsOne() throws Exception {
            world.useRepresentation(RepresentationMode.several(2));
            String documentId = draftAndSubmit("사옥 보증금", "50000000");

            approveStep(documentId, pendingStepId(documentId, approver), approver);
            JsonNode afterOneRep = approveStep(
                    documentId, pendingStepId(documentId, firstRep), firstRep);

            assertThat(afterOneRep.path("document").path("state").asText()).isEqualTo("APPROVED");
        }
    }

    // ------------------------------------------------------------------
    // 회수
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("회수")
    class Recall {

        @Test
        @DisplayName("is refused once anybody has approved, because withdrawing would erase "
                + "a decision that was really made")
        void refusedAfterTheFirstApproval() throws Exception {
            world.useRepresentation(RepresentationMode.jointAll(2));
            String documentId = draftAndSubmit("회수 시험", "50000000");
            approveStep(documentId, pendingStepId(documentId, approver), approver);

            MvcResult result = mockMvc.perform(post("/api/v1/approvals/{id}/recall", documentId)
                    .cookie(drafter)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T17:00:00.000\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(read(result).path("code").asText()).isEqualTo("approval_rule_violated");

            JsonNode detail = readDocument(documentId, drafter);
            assertThat(detail.path("document").path("state").asText())
                    .as("the 부장's step is satisfied and the line has moved on to the 대표 "
                            + "step, so the document is still simply in progress — "
                            + "PARTIALLY_APPROVED is reserved for a quorum step that has some "
                            + "of its signatures, not for any document with one approval")
                    .isEqualTo("IN_PROGRESS");
            assertThat(detail.path("trail"))
                    .as("the approval that already happened stays on the record, and the "
                            + "refused recall added nothing to it")
                    .hasSize(1);
        }

        @Test
        @DisplayName("is allowed before anybody has approved")
        void allowedBeforeAnyApproval() throws Exception {
            String documentId = draftAndSubmit("취소할 문서", "10000");

            JsonNode after = json.readTree(mockMvc.perform(
                    post("/api/v1/approvals/{id}/recall", documentId)
                            .cookie(drafter)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actedAt\":\"2026-08-30T13:00:00.000\","
                                    + "\"comment\":\"금액을 잘못 적었습니다.\"}"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            assertThat(after.path("document").path("state").asText()).isEqualTo("RECALLED");
        }
    }

    // ------------------------------------------------------------------
    // Business time on the wire
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("business instants on the wire")
    class BusinessTime {

        @Test
        @DisplayName("a 27:00 approval round-trips as 27:00 on the business day it belongs "
                + "to, and is not normalised to the next calendar date")
        void lateShiftKeepsItsBusinessDay() throws Exception {
            String documentId = draftAndSubmit("야간 근무 정산", "10000");
            String stepId = pendingStepId(documentId, approver);

            // 03:00 the following morning, on a shift that began the previous
            // evening. It belongs to the 30th and must come back saying so.
            JsonNode after = approveStepAt(documentId, stepId, approver,
                    "2026-08-30T27:00:00.000");

            String actedAt = after.path("trail").get(0).path("actedAt").asText();
            assertThat(actedAt)
                    .as("normalising to 2026-08-31T03:00 would move the approval to a day "
                            + "the approver was not working")
                    .isEqualTo("2026-08-30T27:00:00.000");

            // And it survives a re-read, so it is stored that way rather than
            // being echoed back from the request.
            JsonNode reread = readDocument(documentId, drafter);
            assertThat(reread.path("trail").get(0).path("actedAt").asText())
                    .isEqualTo("2026-08-30T27:00:00.000");
        }

        @Test
        @DisplayName("a trailing Z is rejected loudly rather than trimmed")
        void zuluIsRefused() throws Exception {
            String documentId = draftAndSubmit("타임존 시험", "10000");
            String stepId = pendingStepId(documentId, approver);

            MvcResult result = mockMvc.perform(post(
                    "/api/v1/approvals/{id}/steps/{step}/approve", documentId, stepId)
                    .cookie(approver)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T14:00:00.000Z\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode problem = read(result);
            assertThat(problem.path("code").asText()).isEqualTo("invalid_business_instant");
            assertThat(problem.path("extensions").path("input").asText())
                    .as("the rejected value is handed back so the client can see what it sent")
                    .isEqualTo("2026-08-30T14:00:00.000Z");

            assertThat(stateOf(documentId, approver))
                    .as("nothing was signed on the strength of an instant nobody could parse")
                    .isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("an amount crosses the wire as an exact decimal string in both "
                + "directions")
        void amountsStayExact() throws Exception {
            String documentId = draft("정밀 금액", "1234567.89");

            JsonNode detail = readDocument(documentId, drafter);
            assertThat(detail.path("document").path("amount").isTextual())
                    .as("a JSON number here would let a client parse it into a double and "
                            + "cross a threshold it should not")
                    .isTrue();
            assertThat(detail.path("document").path("amount").asText()).isEqualTo("1234567.89");
        }
    }

    // ------------------------------------------------------------------
    // Preconditions and idempotency
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("concurrency and retries")
    class Concurrency {

        @Test
        @DisplayName("editing a draft without If-Match is refused, and a stale one gets a "
                + "412 carrying the current tag")
        void draftEditsNeedThePrecondition() throws Exception {
            String documentId = draft("금액 수정 전", "10000");
            String tag = etagOf(documentId);

            MvcResult missing = mockMvc.perform(patch(documentId, null, "20000")).andReturn();
            assertThat(missing.getResponse().getStatus()).isEqualTo(428);
            assertThat(read(missing).path("code").asText()).isEqualTo("precondition_required");

            mockMvc.perform(patch(documentId, tag, "20000")).andReturn();

            MvcResult stale = mockMvc.perform(patch(documentId, tag, "30000")).andReturn();
            assertThat(stale.getResponse().getStatus()).isEqualTo(412);
            JsonNode problem = read(stale);
            assertThat(problem.path("code").asText()).isEqualTo("precondition_failed");
            assertThat(problem.path("extensions").path("currentEtag").asText())
                    .as("handing back the current tag turns the retry into one round trip")
                    .isNotEmpty();

            assertThat(readDocument(documentId, drafter).path("document").path("amount").asText())
                    .as("the stale write did not land")
                    .isEqualTo("20000");
        }

        @Test
        @DisplayName("a submitted document cannot be edited at all")
        void submittedDocumentsAreImmutable() throws Exception {
            String documentId = draftAndSubmit("상신 후 수정 시도", "10000");

            MvcResult result = mockMvc.perform(patch(documentId, "*", "99999")).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(read(result).path("code").asText()).isEqualTo("document_state_conflict");
            assertThat(read(result).path("detail").asText()).contains("상신된 문서는 수정할 수 없습니다");
        }

        @Test
        @DisplayName("drafting twice under one idempotency key creates one document")
        void draftIsIdempotent() throws Exception {
            String key = UUID.randomUUID().toString();
            String body = draftBody("중복 방지", "42000");

            MvcResult first = mockMvc.perform(post("/api/v1/approvals")
                    .cookie(drafter).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            MvcResult second = mockMvc.perform(post("/api/v1/approvals")
                    .cookie(drafter).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus())
                    .as("the replay is answered, not re-run")
                    .isEqualTo(200);
            assertThat(read(second).path("id").asText()).isEqualTo(read(first).path("id").asText());

            assertThat(ids(inboxOf(drafter, world.drafterAccountId), "draftedByMe"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("the same key with a different body is refused rather than answered "
                + "with the first result")
        void reusingAKeyForAnotherIntentIsRefused() throws Exception {
            String key = UUID.randomUUID().toString();
            mockMvc.perform(post("/api/v1/approvals")
                    .cookie(drafter).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draftBody("첫 번째", "1000"))).andReturn();

            MvcResult confused = mockMvc.perform(post("/api/v1/approvals")
                    .cookie(drafter).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draftBody("두 번째", "999999"))).andReturn();

            assertThat(confused.getResponse().getStatus()).isEqualTo(409);
            assertThat(read(confused).path("code").asText()).isEqualTo("idempotency_key_reused");
        }

        @Test
        @DisplayName("drafting without an idempotency key is refused and says why")
        void draftNeedsAKey() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/approvals")
                    .cookie(drafter)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draftBody("키 없음", "1000"))).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(result).path("code").asText())
                    .isEqualTo("idempotency_key_required");
        }
    }

    // ------------------------------------------------------------------
    // Line templates
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("결재선 templates")
    class LineTemplates {

        @Test
        @DisplayName("show the 대표 step an amount above the threshold would add, and say why")
        void thresholdIsExplained() throws Exception {
            JsonNode below = json.readTree(mockMvc.perform(
                    get("/api/v1/approvals/line-templates")
                            .cookie(drafter)
                            .param("companyId", world.companyId)
                            .param("documentType", ApiTestWorld.DOCUMENT_TYPE)
                            .param("amount", "4999999.99"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            assertThat(below.path("effectiveSteps")).hasSize(2);
            assertThat(below.path("thresholdExplanation")).isEmpty();

            JsonNode above = json.readTree(mockMvc.perform(
                    get("/api/v1/approvals/line-templates")
                            .cookie(drafter)
                            .param("companyId", world.companyId)
                            .param("documentType", ApiTestWorld.DOCUMENT_TYPE)
                            .param("amount", "5000000"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            assertThat(above.path("effectiveSteps"))
                    .as("the threshold is inclusive, so exactly 5,000,000 crosses it")
                    .hasSize(3);
            assertThat(above.path("thresholdExplanation").get(0).asText())
                    .contains("대표이사 결재");
        }
    }

    // ------------------------------------------------------------------
    // 전결 and 대결
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("전결 and 대결")
    class DelegationAndActing {

        @Test
        @DisplayName("전결 finalises the document and leaves the steps it displaced marked "
                + "SKIPPED rather than approved")
        void delegatedFinalSkipsRatherThanSigns() throws Exception {
            world.useRepresentation(RepresentationMode.several(2));
            String documentId = draftAndSubmit("긴급 집행", "50000000");

            MvcResult result = mockMvc.perform(post(
                    "/api/v1/approvals/{id}/steps/{step}/delegate-final",
                    documentId, pendingStepId(documentId, approver))
                    .cookie(approver)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T15:00:00.000\","
                            + "\"reason\":\"대표이사 해외 출장으로 전결 처리합니다.\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("전결 failed: %s",
                            result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(200);
            JsonNode after = read(result);
            assertThat(after.path("document").path("state").asText()).isEqualTo("APPROVED");

            JsonNode representativeStep = stepAtPosition(after, 2);
            assertThat(representativeStep.path("state").asText())
                    .as("nobody signed the 대표 step, and the trail must not suggest they did")
                    .isEqualTo("SKIPPED");
            assertThat(representativeStep.path("approvalsGiven").asInt()).isZero();
            assertThat(after.path("trail")).hasSize(1);
        }

        @Test
        @DisplayName("전결 is refused when it would skip nothing, because that is an ordinary "
                + "승인 wearing a label an auditor would misread")
        void delegatedFinalNeedsSomethingToSkip() throws Exception {
            // Below the threshold, so the 부장 step is the only 결재 step there is.
            String documentId = draftAndSubmit("소액 지출", "10000");

            MvcResult result = mockMvc.perform(post(
                    "/api/v1/approvals/{id}/steps/{step}/delegate-final",
                    documentId, pendingStepId(documentId, approver))
                    .cookie(approver)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actedAt\":\"2026-08-30T15:00:00.000\","
                            + "\"reason\":\"전결 처리합니다.\"}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(read(result).path("code").asText()).isEqualTo("approval_rule_violated");
            assertThat(read(result).path("detail").asText())
                    .contains("일반 승인으로 처리해 주십시오");
            assertThat(stateOf(documentId, drafter)).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("대결 is refused while the approver is at their desk, and allowed once "
                + "their absence is on the record")
        void actingRequiresARecordedAbsence() throws Exception {
            world.useRepresentation(RepresentationMode.several(2));
            String documentId = draftAndSubmit("대결 시험", "50000000");
            approveStep(documentId, pendingStepId(documentId, approver), approver);
            String representativeStep = pendingStepId(documentId, firstRep);

            MvcResult tooEarly = actFor(documentId, representativeStep);
            assertThat(tooEarly.getResponse().getStatus())
                    .as("signing in someone's name while they are working is not 대결")
                    .isEqualTo(409);
            assertThat(read(tooEarly).path("detail").asText())
                    .contains("대결은 결재자가 부재중일 때만 가능합니다");

            recordAbsenceFor(world.secondRepEmployeeId);

            MvcResult acted = actFor(documentId, representativeStep);
            assertThat(acted.getResponse().getStatus())
                    .as("대결 failed: %s",
                            acted.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(200);

            JsonNode after = read(acted);
            assertThat(after.path("document").path("state").asText()).isEqualTo("APPROVED");

            JsonNode entry = after.path("trail").get(after.path("trail").size() - 1);
            assertThat(entry.path("action").asText()).isEqualTo("ACTING");
            assertThat(entry.path("actorAccountId").asText())
                    .as("the trail says 이대표 acted for 최대표, never that 최대표 approved")
                    .isEqualTo(world.firstRepAccountId);
            assertThat(entry.path("onBehalfOfAccountId").asText())
                    .isEqualTo(world.secondRepAccountId);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 이대표 acting in 최대표's place at a step both were routed to. */
    private MvcResult actFor(String documentId, String stepId) throws Exception {
        return mockMvc.perform(post(
                "/api/v1/approvals/{id}/steps/{step}/act-for", documentId, stepId)
                .cookie(firstRep)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode()
                        .put("actedAt", "2026-08-30T16:00:00.000")
                        .put("absentAccountId", world.secondRepAccountId)
                        .put("reason", "최대표님 휴가 중이라 대결합니다."))))
                .andReturn();
    }

    /**
     * Puts a non-working status on somebody's day.
     *
     * <p>Through the attendance API rather than by writing a flag, because that
     * is the whole route 대결 depends on: "absent" is whatever the installation
     * configured as not counting as working, read from the client's own rows.
     */
    private void recordAbsenceFor(String employeeId) throws Exception {
        MvcResult defined = mockMvc.perform(post("/api/v1/attendance/status-types")
                .cookie(firstRep)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\",\"code\":\"LEAVE\","
                        + "\"labelKo\":\"휴가\",\"countsAsWorking\":false}"))
                .andReturn();
        assertThat(defined.getResponse().getStatus())
                .as("status failed: %s",
                        defined.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);

        MvcResult started = mockMvc.perform(post("/api/v1/attendance/records")
                .cookie(firstRep)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":\"" + world.companyId + "\",\"employeeId\":\""
                        + employeeId + "\",\"statusCode\":\"LEAVE\","
                        + "\"at\":\"2026-08-30T09:00:00.000\"}"))
                .andReturn();
        assertThat(started.getResponse().getStatus())
                .as("record failed: %s",
                        started.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
    }

    private static JsonNode stepAtPosition(JsonNode detail, int position) {
        for (JsonNode step : detail.path("steps")) {
            if (step.path("position").asInt() == position) {
                return step;
            }
        }
        throw new AssertionError("no step at position " + position + " on "
                + detail.path("steps"));
    }

    private String draftBody(String title, String amount) {
        return "{\"companyId\":\"" + world.companyId + "\","
                + "\"documentType\":\"" + ApiTestWorld.DOCUMENT_TYPE + "\","
                + "\"title\":\"" + title + "\","
                + "\"amount\":\"" + amount + "\","
                + "\"currencyCode\":\"KRW\","
                + "\"businessDate\":\"2026-08-30\"}";
    }

    private String draft(String title, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/approvals")
                .cookie(drafter)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody(title, amount)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("draft failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return read(result).path("id").asText();
    }

    private String draftAndSubmit(String title, String amount) throws Exception {
        String documentId = draft(title, amount);
        MvcResult result = mockMvc.perform(post("/api/v1/approvals/{id}/submit", documentId)
                .cookie(drafter)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"submittedAt\":\"2026-08-30T09:30:00.000\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("submit failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return documentId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patch(
            String documentId, String ifMatch, String amount) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/approvals/{id}", documentId)
                        .cookie(drafter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"금액 수정\",\"amount\":\"" + amount
                                + "\",\"currencyCode\":\"KRW\",\"businessDate\":\"2026-08-30\"}");
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private String etagOf(String documentId) throws Exception {
        return mockMvc.perform(get("/api/v1/approvals/{id}", documentId).cookie(drafter))
                .andReturn().getResponse().getHeader("ETag");
    }

    private JsonNode approveStep(String documentId, String stepId, Cookie who) throws Exception {
        return approveStepAt(documentId, stepId, who, "2026-08-30T15:00:00.000");
    }

    private JsonNode approveStepAt(String documentId, String stepId, Cookie who, String actedAt)
            throws Exception {
        MvcResult result = mockMvc.perform(post(
                "/api/v1/approvals/{id}/steps/{step}/approve", documentId, stepId)
                .cookie(who)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actedAt\":\"" + actedAt + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("approve failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return read(result);
    }

    private void returnDocument(String documentId, String reason) throws Exception {
        String stepId = pendingStepId(documentId, approver);
        MvcResult result = mockMvc.perform(post(
                "/api/v1/approvals/{id}/steps/{step}/return", documentId, stepId)
                .cookie(approver)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode()
                        .put("actedAt", "2026-08-30T14:00:00.000")
                        .put("reason", reason))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("return failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
    }

    private JsonNode readDocument(String documentId, Cookie who) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/v1/approvals/{id}", documentId).cookie(who)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("read failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return read(result);
    }

    private String stateOf(String documentId, Cookie who) throws Exception {
        return readDocument(documentId, who).path("document").path("state").asText();
    }

    private int trailSize(String documentId, Cookie who) throws Exception {
        return readDocument(documentId, who).path("trail").size();
    }

    private String pendingStepId(String documentId, Cookie who) throws Exception {
        return stepWithState(readDocument(documentId, who), "PENDING").path("id").asText();
    }

    private static JsonNode stepWithState(JsonNode detail, String state) {
        for (JsonNode step : detail.path("steps")) {
            if (state.equals(step.path("state").asText())) {
                return step;
            }
        }
        throw new AssertionError("no step in state " + state + " on " + detail.path("steps"));
    }

    private JsonNode inboxOf(Cookie who, String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/approvals/inbox")
                .cookie(who)
                .param("companyId", world.companyId)
                .param("accountId", accountId))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("inbox failed: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return read(result);
    }

    private static java.util.List<String> ids(JsonNode inbox, String bucket) {
        java.util.List<String> found = new java.util.ArrayList<String>();
        for (JsonNode row : inbox.path(bucket)) {
            found.add(row.path("id").asText());
        }
        return found;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
