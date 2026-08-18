# Sizing

Target: **4 vCPU / 12 GB, ~300 concurrent users, one box.**

## Where the memory goes

| Component | Resident | Notes |
|---|---|---|
| PostgreSQL | ~2 GB | `shared_buffers` 25% of what it is given |
| API (JVM) | ~2 GB | `-XX:MaxRAMPercentage=70`; 20 connection pool |
| Conversion worker | **~1 GB + 250 MB per LibreOffice process** | the reason this box is 12 GB |
| nginx + SPA | ~100 MB | static files |

## Why the conversion pool is capped, and at 3

A LibreOffice process holds 150–300 MB resident while converting, more for a
document with large images. On 4 vCPU the arithmetic that matters is memory,
not CPU: an unbounded pool exhausts RAM long before it saturates the cores, and
the box starts swapping instead of refusing work — which turns a slow export
into a whole-system outage.

`COREINTRA_CONVERSION_POOL_SIZE=3` leaves roughly 1 GB of headroom at 12 GB with
the API and database alongside. Raising it needs more RAM, not more CPU.

Measured on the development machine: a small 결재 document converts DOCX→PDF in
**about one second**. At pool 3 that is roughly 3 conversions/second sustained,
which is far above what 300 users generate — exports are bursty (month-end,
board packs) rather than continuous, and the queue is there to absorb the burst.

**The tradeoff, stated plainly:** a burst of large exports queues rather than
running in parallel. Users see a job id and a progress indicator instead of an
immediate download. That is deliberate — the alternative is an out-of-memory
kill that takes the intranet down with it, and an intranet that stays up while
an export is slow is strictly better than one that does not.

## Sync versus async

`COREINTRA_CONVERSION_SYNC_TIMEOUT_MS=8000`. A document that converts inside
that window is returned directly; anything slower becomes a job id the client
polls. Conversion never occupies an API request thread.

## Scaling past one box

The conversion worker is stateless and talks HTTP, so it is the first thing to
move: run it on a second machine and point `COREINTRA_CONVERSION_URL` at it.
Nothing else in the deployment assumes co-location.
