package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.service.ConversionJobService;
import com.coreintra.documents.support.Fakes;

/**
 * The queue in front of LibreOffice, and the failure mode it exists to make survivable.
 *
 * <p>"The conversion worker being down produces a clear, actionable error and never a
 * corrupted or truncated file" is a named acceptance test. The half of it that lives here
 * is that a job ends either naming a render or naming an error - there is no state in
 * which something half-written is downloadable, because a failure path writes no output at
 * all.
 */
class ConversionQueueTest {

    private static final String COMPANY = "company-1";
    private static final String DOCUMENT = "doc-1";
    private static final String FINGERPRINT =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String SOURCE_BLOB =
            "00000000000000000000000000000000000000000000000000000000000000aa";

    private Fakes.ConversionJobs jobRows;
    private ConversionJobService jobs;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        jobRows = new Fakes.ConversionJobs();
        jobs = new ConversionJobService(jobRows);
        now = OffsetDateTime.now();
    }

    private ConversionJobEntity enqueue() {
        return jobs.enqueue(COMPANY, DOCUMENT, 1, SOURCE_BLOB, DocumentFormat.DOCX,
                RenderFormat.PDF, FINGERPRINT, "account-1");
    }

    @Nested
    @DisplayName("enqueue is idempotent")
    class Idempotent {

        @Test
        @DisplayName("pressing Export twice joins the first job rather than starting a second")
        void theSecondRequestJoinsTheFirst() {
            ConversionJobEntity first = enqueue();
            ConversionJobEntity second = enqueue();

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(jobRows.count())
                    .describedAs("a second row would be a second LibreOffice on one document")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a different render configuration is a different job")
        void aDifferentConfigurationIsDifferentWork() {
            enqueue();

            jobs.enqueue(COMPANY, DOCUMENT, 1, SOURCE_BLOB, DocumentFormat.DOCX, RenderFormat.PDF,
                    "0000000000000000000000000000000000000000000000000000000000000002",
                    "account-1");

            assertThat(jobRows.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("a different target format is a different job")
        void aDifferentFormatIsDifferentWork() {
            enqueue();

            jobs.enqueue(COMPANY, DOCUMENT, 1, SOURCE_BLOB, DocumentFormat.DOCX,
                    RenderFormat.HWPX, FINGERPRINT, "account-1");

            assertThat(jobRows.count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("leases are exclusive and expire")
    class Leasing {

        @Test
        @DisplayName("a leased job is not offered to a second worker")
        void oneWorkerAtATime() {
            enqueue();

            Optional<ConversionJobEntity> first = jobs.lease("worker-a", Duration.ofMinutes(5), now);
            Optional<ConversionJobEntity> second = jobs.lease("worker-b", Duration.ofMinutes(5), now);

            assertThat(first).isPresent();
            assertThat(first.get().state()).isEqualTo(ConversionJobEntity.State.RUNNING);
            assertThat(first.get().attemptCount()).isEqualTo(1);
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("a lapsed lease returns the job to the queue, because a dead worker holds nothing")
        void aLapsedLeaseIsReclaimed() {
            enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);

            Optional<ConversionJobEntity> reclaimed =
                    jobs.lease("worker-b", Duration.ofMinutes(5), now.plusMinutes(6));

            assertThat(reclaimed).isPresent();
            assertThat(reclaimed.get().leaseOwner()).isEqualTo("worker-b");
            assertThat(reclaimed.get().attemptCount())
                    .describedAs("the reclaim is an attempt: it counts against the bound")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an anonymous lease is refused, because it could never be reclaimed")
        void leasesAreHeldByNamedWorkers() {
            enqueue();

            assertThatThrownBy(() -> jobs.lease("  ", Duration.ofMinutes(5), now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("named worker");
        }

        @Test
        @DisplayName("an empty queue is empty, which is not the same as the worker being down")
        void nothingToDoIsItsOwnAnswer() {
            assertThat(jobs.lease("worker-a", Duration.ofMinutes(5), now)).isEmpty();
        }
    }

    @Nested
    @DisplayName("attempts are bounded and failures are legible")
    class Bounded {

        @Test
        @DisplayName("a job that keeps failing is abandoned rather than retried forever")
        void retriesRunOut() {
            ConversionJobEntity job = enqueue();

            for (int attempt = 1; attempt <= ConversionJobService.DEFAULT_MAX_ATTEMPTS; attempt++) {
                jobs.lease("worker-a", Duration.ofMinutes(5), now);
                jobs.fail(job.id(), "RENDERER_CRASHED",
                        "LibreOffice exited with status 139 (SIGSEGV) converting page 4", now);
            }

            ConversionJobEntity abandoned = jobs.find(job.id()).get();
            assertThat(abandoned.state()).isEqualTo(ConversionJobEntity.State.ABANDONED);
            assertThat(abandoned.attemptCount())
                    .isEqualTo(ConversionJobService.DEFAULT_MAX_ATTEMPTS);
            assertThat(jobs.lease("worker-a", Duration.ofMinutes(5), now))
                    .describedAs("nobody will retry it; someone has to look")
                    .isEmpty();
            assertThat(jobs.abandoned()).extracting(ConversionJobEntity::id).containsExactly(job.id());
        }

        /**
         * ACCEPTANCE: the worker being down produces a clear, actionable error and never a
         * corrupted or truncated file.
         */
        @Test
        @DisplayName("a failed job carries the error and names no output at all")
        void failureLeavesAnErrorAndNoArtefact() {
            ConversionJobEntity job = enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);

            ConversionJobEntity failed = jobs.fail(job.id(), "WORKER_UNREACHABLE",
                    "conversion worker unreachable at http://localhost:3000; check the "
                    + "container is running", now);

            assertThat(failed.errorCode())
                    .describedAs("ours, and the thing the UI translates into Korean")
                    .isEqualTo("WORKER_UNREACHABLE");
            assertThat(failed.errorDetail()).contains("check the container is running");
            assertThat(failed.renderId())
                    .describedAs("no render means no partial file for the export UI to serve")
                    .isNull();
            assertThat(failed.state()).isEqualTo(ConversionJobEntity.State.FAILED);
        }

        @Test
        @DisplayName("\"it failed\" is not an error message and is refused")
        void failuresMustSayWhat() {
            ConversionJobEntity job = enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);

            assertThatThrownBy(() -> jobs.fail(job.id(), "   ", "something happened", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("what went wrong");
        }

        @Test
        @DisplayName("succeeding without a render is refused")
        void successNamesItsArtefact() {
            ConversionJobEntity job = enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);

            assertThatThrownBy(() -> jobs.complete(job.id(), null, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("without producing a render");
        }

        @Test
        @DisplayName("a completed job names its render and holds no lease")
        void successReleasesTheLease() {
            ConversionJobEntity job = enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);

            ConversionJobEntity done = jobs.complete(job.id(), "render-1", now);

            assertThat(done.state()).isEqualTo(ConversionJobEntity.State.SUCCEEDED);
            assertThat(done.renderId()).isEqualTo("render-1");
            assertThat(done.leaseOwner()).isNull();
            assertThat(done.finishedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("a failure with attempts left is claimable again")
        void transientFailuresRetry() {
            ConversionJobEntity job = enqueue();
            jobs.lease("worker-a", Duration.ofMinutes(5), now);
            jobs.fail(job.id(), "WORKER_RESTARTED", "worker restarted mid-conversion", now);

            assertThat(jobs.lease("worker-b", Duration.ofMinutes(5), now)).isPresent();
        }
    }
}
