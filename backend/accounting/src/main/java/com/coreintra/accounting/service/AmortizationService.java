package com.coreintra.accounting.service;

import com.coreintra.accounting.domain.AmortizationSchedule;
import com.coreintra.accounting.domain.Amount;
import com.coreintra.accounting.domain.Batch;
import com.coreintra.accounting.domain.BatchKind;
import com.coreintra.accounting.domain.Posting;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Amortisation: compute and preview, and nothing else.
 *
 * <h2>Nothing is generated behind anyone's back</h2>
 *
 * <p>{@link #preview} writes nothing. It returns the schedule and the entries it would make, for a
 * person to look at. {@link #post} is that person accepting them, and it writes them once, as an
 * ordinary batch of ordinary entries that can be voided like any others.
 *
 * <p>The parameters are stored on the batch as lineage and are never re-executed. There is no
 * schedule table for a job to pick up, and that absence is deliberate: a monthly job that re-runs
 * a generator posts entries nobody read, against a chart of accounts that has moved on, and the
 * first sign of trouble is a figure in a report that no human ever approved. What a reader needs
 * a year later is what was decided and by whom, which is exactly what the lineage holds.
 *
 * <h2>Why a preview is checked at all, and against what</h2>
 *
 * <p>{@link #preview} writes nothing, so it could plausibly be free. It is checked against
 * {@code accounting.batch:create} - the permission for the only thing the result can be used for.
 * A preview handed to an account that may not post it is a set of sixty ready-made entries in the
 * hands of somebody who has to ask a colleague to run them, which is how a control gets routed
 * around socially rather than technically.
 */
public class AmortizationService {

    private final BatchService batches;
    private final AccountingGate gate;

    public AmortizationService(BatchService batches, AccountingGate gate) {
        this.batches = batches;
        this.gate = gate;
    }

    /**
     * Computes the schedule and the entries it implies. Writes nothing.
     *
     * @param businessDate the date the decision is being taken on; the instalments carry their own
     *     dates, and each is authorised again on its own when {@link #post} writes them
     */
    public Preview preview(PermissionPrincipal caller, String bookId, Request request,
            LocalDate businessDate) {
        gate.requireOnBook(caller, AccountingPermissions.BATCH_CREATE, bookId, businessDate,
                "preview an amortisation schedule for book " + bookId);
        if (request == null) {
            throw new IllegalArgumentException("there is nothing to amortise");
        }
        request.validate();
        AmortizationSchedule schedule = AmortizationSchedule.straightLine(request.base,
                request.residual, request.startMonth, request.months, request.roundingDigits,
                request.remainderTo, request.postingDay);

        List<NewEntry> entries = new ArrayList<NewEntry>();
        for (AmortizationSchedule.Instalment line : schedule.instalments()) {
            if (line.amount().isZero()) {
                // A zero instalment is a posting that says nothing, and Posting refuses it. Better
                // to say why here than to fail on line 7 of 60 with "a posting cannot be zero".
                throw new IllegalArgumentException("instalment " + line.number() + " rounds to zero"
                        + " at " + request.roundingDigits + " decimal(s). Use more decimals, fewer"
                        + " months, or a larger base amount.");
            }
            Posting debit = Posting.of(request.debitAccountId, line.amount());
            Posting credit = Posting.of(request.creditAccountId, line.amount().negate());
            if (request.clientId != null) {
                debit = debit.withClient(request.clientId);
                credit = credit.withClient(request.clientId);
            }
            String description = request.description + " (" + line.number() + "/"
                    + schedule.months() + ")";
            entries.add(new NewEntry(BusinessInstant.of(line.businessDate(), request.offsetSeconds),
                    description, Immutables.listOf(debit, credit)));
        }
        return new Preview(schedule, entries, lineageOf(request));
    }

    /**
     * Writes a preview that a person has accepted, as one AMORTIZATION batch.
     *
     * <p>Takes the preview rather than the request, so what is written is what was shown. Passing
     * the request again would let the schedule be recomputed between the screen and the save.
     */
    @Transactional
    public Batch post(PermissionPrincipal caller, String bookId, String label, Preview preview) {
        if (preview == null) {
            throw new IllegalArgumentException("there is no preview to post");
        }
        return batches.write(caller, bookId, BatchKind.AMORTIZATION, label, null,
                preview.generatorParams(), preview.entries());
    }

    /**
     * The lineage recorded beside the batch: what was asked for, in a form that still reads when
     * the generator that produced it is three versions gone. Amounts are exact decimal strings,
     * never JSON numbers, for the same reason they are on the wire.
     */
    private static String lineageOf(Request request) {
        StringBuilder json = new StringBuilder();
        json.append("{\"generator\":\"straight-line-amortisation\"")
            .append(",\"baseAmount\":").append(quote(request.base.toExactString()))
            .append(",\"residualValue\":").append(quote(request.residual.toExactString()))
            .append(",\"startMonth\":").append(quote(request.startMonth.toString()))
            .append(",\"months\":").append(request.months)
            .append(",\"roundingDigits\":").append(request.roundingDigits)
            .append(",\"remainderTo\":").append(quote(request.remainderTo.name()))
            .append(",\"postingDay\":").append(request.postingDay)
            .append(",\"offsetSeconds\":").append(request.offsetSeconds)
            .append(",\"debitAccountId\":").append(quote(request.debitAccountId))
            .append(",\"creditAccountId\":").append(quote(request.creditAccountId))
            .append(",\"clientId\":").append(quote(request.clientId))
            .append(",\"description\":").append(quote(request.description))
            .append(",\"neverReExecuted\":true}");
        return json.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                quoted.append('\\').append(character);
            } else if (character < 0x20) {
                quoted.append(' ');
            } else {
                quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }

    /** What to amortise, over how long, and where the two sides go. */
    public static final class Request {

        private final String debitAccountId;
        private final String creditAccountId;
        private final Amount base;
        private final Amount residual;
        private final YearMonth startMonth;
        private final int months;
        private final int roundingDigits;
        private final AmortizationSchedule.Remainder remainderTo;
        private final int postingDay;
        private final int offsetSeconds;
        private final String description;
        private final String clientId;

        /**
         * @param debitAccountId usually the expense being recognised
         * @param creditAccountId the asset being consumed, its accumulated-depreciation contra, or
         *     the deferred-revenue liability being released
         * @param offsetSeconds where in the business day each instalment falls (ADR 0002)
         */
        public Request(String debitAccountId, String creditAccountId, Amount base, Amount residual,
                YearMonth startMonth, int months, int roundingDigits,
                AmortizationSchedule.Remainder remainderTo, int postingDay, int offsetSeconds,
                String description, String clientId) {
            this.debitAccountId = debitAccountId;
            this.creditAccountId = creditAccountId;
            this.base = base;
            this.residual = residual;
            this.startMonth = startMonth;
            this.months = months;
            this.roundingDigits = roundingDigits;
            this.remainderTo = remainderTo;
            this.postingDay = postingDay;
            this.offsetSeconds = offsetSeconds;
            this.description = description;
            this.clientId = clientId;
        }

        void validate() {
            if (Texts.isBlank(debitAccountId) || Texts.isBlank(creditAccountId)) {
                throw new IllegalArgumentException("amortisation needs both sides of the entry");
            }
            if (debitAccountId.equals(creditAccountId)) {
                throw new IllegalArgumentException("account " + debitAccountId + " is on both sides"
                        + ", so every instalment would be an entry that does nothing");
            }
            if (Texts.isBlank(description)) {
                throw new IllegalArgumentException("each instalment needs a description: sixty "
                        + "unexplained entries is what this feature exists to avoid");
            }
        }

        public String debitAccountId() {
            return debitAccountId;
        }

        public String creditAccountId() {
            return creditAccountId;
        }

        public Amount base() {
            return base;
        }

        public Amount residual() {
            return residual;
        }
    }

    /** The schedule, the entries it implies, and the lineage to store with them. */
    public static final class Preview {

        private final AmortizationSchedule schedule;
        private final List<NewEntry> entries;
        private final String generatorParams;

        Preview(AmortizationSchedule schedule, List<NewEntry> entries, String generatorParams) {
            this.schedule = schedule;
            this.entries = Immutables.copyOf(entries);
            this.generatorParams = generatorParams;
        }

        public AmortizationSchedule schedule() {
            return schedule;
        }

        /** Ready to post, and not posted. Someone has to say yes first. */
        public List<NewEntry> entries() {
            return entries;
        }

        public String generatorParams() {
            return generatorParams;
        }
    }
}
