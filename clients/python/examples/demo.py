"""Runnable end-to-end demo: one process that both enqueues and executes jobs.

    docker compose up -d          # from the repo root: Postgres, RabbitMQ, scheduler-infra
    python examples/demo.py

Watch it on the dashboard at http://localhost:8080 (admin / admin) while it runs.

The shape here — build a registry, start a worker, enqueue through a scheduler — is the
same one a real service uses; a real one would just register the worker at startup and
enqueue from its request handlers.
"""

from __future__ import annotations

import asyncio
import logging
import os
import random
import sys
from dataclasses import dataclass, field

from taskscheduler import (
    ExponentialBackoff,
    HandlerRegistry,
    JobContext,
    NonRetriableError,
    RabbitConfig,
    Scheduler,
    SchedulerConfig,
    WorkerConfig,
    WorkerPool,
    job_type,
)

DSN = os.environ.get(
    "TASKSCHEDULER_DSN", "postgresql://scheduler:scheduler@localhost:5432/scheduler"
)
AMQP = os.environ.get("TASKSCHEDULER_AMQP", "amqp://scheduler:scheduler@localhost:5672/")
QUEUE = "python-demo"

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s"
)
log = logging.getLogger("demo")


# --- payloads --------------------------------------------------------------------------


@job_type
@dataclass
class SendEmail:
    user_id: int
    template: str


@job_type
@dataclass
class ChargeCard:
    order_id: int
    cents: int
    #: Flip to see the retry path: the handler fails until its last attempt.
    flaky: bool = False


@job_type
@dataclass
class ReindexCatalog:
    product_ids: list[int] = field(default_factory=list)


# --- handlers --------------------------------------------------------------------------

registry = HandlerRegistry()


@registry.handler(SendEmail)
async def send_email(ctx: JobContext, job: SendEmail) -> None:
    log.info("sending %s to user %s (attempt %s)", job.template, job.user_id, ctx.attempt)
    await asyncio.sleep(0.4)


@registry.handler(ChargeCard, retry_policy=ExponentialBackoff(max_attempts=3, initial_seconds=1.0))
async def charge_card(ctx: JobContext, job: ChargeCard) -> None:
    if job.cents <= 0:
        # Retrying will not make a negative amount valid — fail now and stop.
        raise NonRetriableError(f"refusing to charge {job.cents} cents")
    if job.flaky and ctx.attempt < ctx.max_attempts:
        raise RuntimeError(f"payment gateway timed out (attempt {ctx.attempt})")
    log.info("charged order %s: %s cents", job.order_id, job.cents)


@registry.handler(ReindexCatalog)
async def reindex_catalog(ctx: JobContext, job: ReindexCatalog) -> None:
    """Long-running job: reports progress and stops when asked to."""
    bar = ctx.progress_bar(len(job.product_ids))
    for product_id in job.product_ids:
        if await ctx.is_cancellation_requested():
            log.info("reindex cancelled after %s items", bar.processed)
            return
        await asyncio.sleep(0.2)
        if random.random() < 0.1:
            await bar.failed(message=f"product {product_id} rejected")
        else:
            await bar.succeeded()
    log.info("reindexed %s/%s products", bar.succeeded_count, bar.total)


# --- wiring ----------------------------------------------------------------------------


async def main() -> None:
    scheduler_config = SchedulerConfig(dsn=DSN, node_id="python-demo-1", default_queue=QUEUE)
    worker_config = WorkerConfig(node_id="python-demo-1", node_tags=["demo", "python"])
    worker_config.queue(QUEUE, concurrency=4)

    worker = WorkerPool(
        scheduler_config=scheduler_config,
        worker_config=worker_config,
        rabbit_config=RabbitConfig(url=AMQP, queues=[QUEUE]),
        registry=registry,
    )

    async with Scheduler(scheduler_config, registry=registry) as scheduler, worker:
        await scheduler.enqueue(SendEmail(user_id=42, template="welcome"))
        await scheduler.enqueue(ChargeCard(order_id=1001, cents=4999), priority=8)
        await scheduler.enqueue(ChargeCard(order_id=1002, cents=2500, flaky=True))
        await scheduler.enqueue(ChargeCard(order_id=1003, cents=0))  # fails, no retries

        # A chain: each step waits for the previous one to succeed.
        await scheduler.chain(
            SendEmail(user_id=7, template="step-1"),
            SendEmail(user_id=7, template="step-2"),
        )

        # A barrier: run once both fan-out jobs have finished.
        first = await scheduler.enqueue(SendEmail(user_id=8, template="warm-cache-a"))
        second = await scheduler.enqueue(SendEmail(user_id=8, template="warm-cache-b"))
        await scheduler.enqueue_after(
            SendEmail(user_id=8, template="cache-warm"), wait_for=[first, second]
        )

        # A long one — cancel it halfway to watch cooperative cancellation.
        reindex_id = await scheduler.enqueue(ReindexCatalog(product_ids=list(range(30))))

        # At most one active job per key, however often this runs.
        await scheduler.enqueue_once("nightly-sync", SendEmail(user_id=1, template="sync"))

        # Fires nightly, driven by scheduler-infra rather than this process.
        await scheduler.recurring(
            "python-demo-nightly", "0 3 * * *", SendEmail(user_id=1, template="nightly"),
            queue=QUEUE, timezone_name="Europe/Berlin",
        )

        log.info("jobs enqueued — cancelling the reindex in 3s")
        await asyncio.sleep(3)
        log.info("cancel(%s) -> %s", reindex_id, await scheduler.cancel(reindex_id, by="demo"))

        log.info("running for 20s; press Ctrl+C to stop early")
        await asyncio.sleep(20)


if __name__ == "__main__":
    if sys.platform == "win32":
        # psycopg cannot run on Windows' default ProactorEventLoop.
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("interrupted")
