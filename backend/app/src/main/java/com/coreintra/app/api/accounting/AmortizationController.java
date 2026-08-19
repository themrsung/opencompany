package com.coreintra.app.api.accounting;

import com.coreintra.accounting.domain.AmortizationSchedule;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.accounting.service.AmortizationService;
import com.coreintra.accounting.service.NewEntry;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.businesstime.BusinessInstant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Amortisation: compute and preview, and nothing else.
 *
 * <h2>There is deliberately no endpoint that posts a schedule</h2>
 *
 * <p>This one returns the schedule and the entries it implies, and stops. To write them, the
 * client posts them to {@code POST /books/{bookId}/batches} with {@code kind: "AMORTIZATION"} and
 * the {@code generatorParams} this response carries — an ordinary batch of ordinary entries, with
 * an idempotency key, voidable like any others.
 *
 * <p>That is one more round trip than a "preview and post" endpoint would be, and the cost is
 * accepted. §9 says compute-and-preview only, returning ready-to-post entries that a human then
 * posts. A server-side handle to a preview would be a schedule the server is holding on somebody's
 * behalf, and the next request after that is always "just run it for me on the first of the
 * month". The parameters are stored on the batch as lineage and are never re-executed; there is no
 * schedule table for a job to pick up, and that absence is the feature.
 */
@RestController
@RequestMapping("/api/v1/accounting/books/{bookId}/amortisation")
@AccountingEnabled
@Tag(name = "Accounting: amortisation",
        description = "Straight-line schedules, computed and shown. Nothing is written here and "
                + "nothing is scheduled to run later.")
public class AmortizationController {

    private final AmortizationService amortization;
    private final CurrentPrincipal current;

    public AmortizationController(AmortizationService amortization, CurrentPrincipal current) {
        this.amortization = amortization;
        this.current = current;
    }

    @PostMapping("/preview")
    @Operation(summary = "Compute a straight-line schedule and the entries it implies",
            description = "Requires accounting.batch:create — the authority for the only thing "
                    + "the result can be used for. Writes nothing. The schedule sums to base "
                    + "minus residual exactly: the rounding remainder is placed at one end, never "
                    + "spread, so no instalment is silently adjusted.")
    public PreviewResponse preview(@PathVariable("bookId") String bookId,
            @Valid @RequestBody PreviewRequest request) {
        AmortizationService.Preview preview = amortization.preview(current.require(), bookId,
                request.toDomain(), Amounts.businessDateOrToday(request.getBusinessDate()));
        return new PreviewResponse(preview);
    }

    // ------------------------------------------------------------------
    // Wire types
    // ------------------------------------------------------------------

    /** What to amortise, over how long, and where the two sides go. */
    public static class PreviewRequest {
        @NotBlank
        private String debitAccountId;
        @NotBlank
        private String creditAccountId;
        @NotBlank
        private String baseAmount;
        private String residualValue;
        @NotBlank
        private String startMonth;
        private int months;
        private int roundingDigits;
        private String remainderTo;
        private int postingDay;
        private int offsetSeconds;
        @NotBlank
        private String description;
        private String clientId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        /** Usually the expense being recognised. */
        public String getDebitAccountId() {
            return debitAccountId;
        }

        public void setDebitAccountId(String value) {
            this.debitAccountId = value;
        }

        /**
         * The asset being consumed, its accumulated-depreciation contra, or the deferred-revenue
         * liability being released.
         */
        public String getCreditAccountId() {
            return creditAccountId;
        }

        public void setCreditAccountId(String value) {
            this.creditAccountId = value;
        }

        /** An exact decimal string, like every other amount here. */
        public String getBaseAmount() {
            return baseAmount;
        }

        public void setBaseAmount(String value) {
            this.baseAmount = value;
        }

        /** What is left at the end. Defaults to zero. */
        public String getResidualValue() {
            return residualValue;
        }

        public void setResidualValue(String value) {
            this.residualValue = value;
        }

        /** {@code YYYY-MM}. */
        public String getStartMonth() {
            return startMonth;
        }

        public void setStartMonth(String value) {
            this.startMonth = value;
        }

        public int getMonths() {
            return months;
        }

        public void setMonths(int value) {
            this.months = value;
        }

        /** How many decimals each instalment is computed to. Not a currency's displayDecimals. */
        public int getRoundingDigits() {
            return roundingDigits;
        }

        public void setRoundingDigits(int value) {
            this.roundingDigits = value;
        }

        /** {@code START} or {@code END}: which end of the schedule carries the remainder. */
        public String getRemainderTo() {
            return remainderTo;
        }

        public void setRemainderTo(String value) {
            this.remainderTo = value;
        }

        /** Day of the month each instalment falls on, clamped to the month's length. */
        public int getPostingDay() {
            return postingDay;
        }

        public void setPostingDay(int value) {
            this.postingDay = value;
        }

        /** Where in the business day each instalment falls (ADR 0002). */
        public int getOffsetSeconds() {
            return offsetSeconds;
        }

        public void setOffsetSeconds(int value) {
            this.offsetSeconds = value;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String value) {
            this.description = value;
        }

        /** Applied to both legs of every instalment. */
        public String getClientId() {
            return clientId;
        }

        public void setClientId(String value) {
            this.clientId = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }

        AmortizationService.Request toDomain() {
            return new AmortizationService.Request(debitAccountId, creditAccountId,
                    Amounts.parse(baseAmount, "baseAmount"),
                    residualValue == null || residualValue.trim().isEmpty()
                            ? Amount.ZERO
                            : Amounts.parse(residualValue, "residualValue"),
                    YearMonth.parse(startMonth), months, roundingDigits,
                    AmortizationSchedule.Remainder.valueOf(
                            remainderTo == null ? "END" : remainderTo),
                    postingDay, offsetSeconds, description, clientId);
        }
    }

    /** One instalment of the computed schedule. */
    public static class InstalmentResponse {
        private final int number;
        private final LocalDate businessDate;
        private final String amount;
        private final String carryingAmount;

        InstalmentResponse(AmortizationSchedule.Instalment line) {
            this.number = line.number();
            this.businessDate = line.businessDate();
            this.amount = Amounts.wire(line.amount());
            this.carryingAmount = Amounts.wire(line.carryingAmount());
        }

        public int getNumber() {
            return number;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public String getAmount() {
            return amount;
        }

        /** What is left after this instalment. */
        public String getCarryingAmount() {
            return carryingAmount;
        }
    }

    /** The schedule, the entries it implies, and the lineage to store with them. */
    public static class PreviewResponse {
        private final String baseAmount;
        private final String residualValue;
        private final int months;
        private final int roundingDigits;
        private final String remainderTo;
        private final String total;
        private final List<InstalmentResponse> instalments;
        private final List<PendingEntryResponse> entries;
        private final String generatorParams;

        PreviewResponse(AmortizationService.Preview preview) {
            AmortizationSchedule schedule = preview.schedule();
            this.baseAmount = Amounts.wire(schedule.base());
            this.residualValue = Amounts.wire(schedule.residual());
            this.months = schedule.months();
            this.roundingDigits = schedule.roundingDigits();
            this.remainderTo = schedule.remainderTo().name();
            this.total = Amounts.wire(schedule.total());
            this.instalments = new ArrayList<InstalmentResponse>();
            for (AmortizationSchedule.Instalment line : schedule.instalments()) {
                this.instalments.add(new InstalmentResponse(line));
            }
            this.entries = new ArrayList<PendingEntryResponse>();
            for (NewEntry entry : preview.entries()) {
                this.entries.add(new PendingEntryResponse(entry));
            }
            this.generatorParams = preview.generatorParams();
        }

        public String getBaseAmount() {
            return baseAmount;
        }

        public String getResidualValue() {
            return residualValue;
        }

        public int getMonths() {
            return months;
        }

        public int getRoundingDigits() {
            return roundingDigits;
        }

        public String getRemainderTo() {
            return remainderTo;
        }

        /** The instalments summed. Equals base minus residual, exactly. */
        public String getTotal() {
            return total;
        }

        public List<InstalmentResponse> getInstalments() {
            return instalments;
        }

        /**
         * The entries this schedule would write. Not written.
         *
         * <p>Shaped so they can be sent straight back to {@code POST /books/{bookId}/batches}.
         */
        public List<PendingEntryResponse> getEntries() {
            return entries;
        }

        /**
         * What was asked for, in the form that is stored beside the batch as lineage.
         *
         * <p>Pass it back on the batch. Amounts inside it are exact decimal strings, never JSON
         * numbers, for the same reason they are everywhere else here.
         */
        public String getGeneratorParams() {
            return generatorParams;
        }
    }

    /** An entry that has been computed and not written. */
    public static class PendingEntryResponse {
        private final BusinessInstant postedAt;
        private final String description;
        private final List<JournalController.PostingResponse> postings;

        PendingEntryResponse(NewEntry entry) {
            this.postedAt = entry.postedAt();
            this.description = entry.description();
            this.postings = new ArrayList<JournalController.PostingResponse>();
            for (Posting posting : entry.postings()) {
                this.postings.add(new JournalController.PostingResponse(posting));
            }
        }

        public BusinessInstant getPostedAt() {
            return postedAt;
        }

        public String getDescription() {
            return description;
        }

        public List<JournalController.PostingResponse> getPostings() {
            return postings;
        }
    }
}
