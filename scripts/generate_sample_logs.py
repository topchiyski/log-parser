"""Generates the bundled sample logs deterministically (fixed seed).

Usage: python generate_sample_logs.py <output-dir>
"""
import random
import sys
from datetime import datetime, timedelta
from pathlib import Path

DAY = datetime(2026, 10, 26)

USERS = [f"user-{n}" for n in range(1000, 1400)]
ENDPOINTS = ["/api/orders", "/api/cart", "/api/products", "/api/users", "/api/checkout", "/api/search"]
JOBS = ["invoice-export", "stock-sync", "report-builder", "email-digest"]


def info(r):
    return r.choice([
        lambda: f"User {r.choice(USERS)} logged in",
        lambda: f"User {r.choice(USERS)} logged out",
        lambda: f"Order {r.randint(100000, 999999)} created for {r.choice(USERS)}",
        lambda: f"Order {r.randint(100000, 999999)} shipped",
        lambda: f"Payment {r.randint(100000, 999999)} authorized",
        lambda: f"GET {r.choice(ENDPOINTS)} 200 in {r.randint(12, 380)}ms",
        lambda: f"POST {r.choice(ENDPOINTS)} 201 in {r.randint(30, 600)}ms",
        lambda: f"Scheduled job {r.choice(JOBS)} completed in {r.randint(1, 90)}s",
        lambda: f"Email sent to {r.choice(USERS)}",
    ])()


def debug(r):
    return r.choice([
        lambda: f"Cache hit ratio: {r.uniform(0.6, 0.99):.2f}",
        lambda: f"Cache miss for product {r.randint(1, 5000)}",
        lambda: f"SQL query took {r.randint(1, 120)}ms",
        lambda: f"Session {r.randint(10**7, 10**8)} refreshed",
        lambda: f"Feature flag new-checkout evaluated to {r.choice(['true', 'false'])}",
        lambda: f"Connection pool: {r.randint(2, 18)}/50 in use",
    ])()


def warning(r):
    return r.choice([
        lambda: f"Response time above 2s for {r.choice(ENDPOINTS)}",
        lambda: f"Disk usage at {r.randint(80, 92)}%",
        lambda: f"Retrying request to inventory-service (attempt {r.randint(1, 3)})",
        lambda: f"Slow query detected: {r.randint(1500, 4000)}ms",
        lambda: "Deprecated API version v1 called",
    ])()


# Error messages are fixed strings so repeated errors can be grouped.
NORMAL_ERRORS = [
    "Failed to process payment: timeout",
    "Payment retry failed",
    "Could not send email: SMTP connection refused",
    "Order validation failed: missing shipping address",
    "Cache eviction failed",
    "Inventory service returned 503",
]
WEIGHTS = {"DEBUG": 0.25, "INFO": 0.56, "WARNING": 0.15, "ERROR": 0.04}

STACK_FRAMES = [
    "    at com.example.payment.PaymentService.charge(PaymentService.java:142)",
    "    at com.example.payment.PaymentController.checkout(PaymentController.java:88)",
    "    at com.example.db.ConnectionPool.acquire(ConnectionPool.java:57)",
    "    at com.example.orders.OrderService.place(OrderService.java:211)",
    "    at java.base/java.lang.Thread.run(Thread.java:1583)",
]
CAUSES = [
    "Caused by: java.net.SocketTimeoutException: Read timed out",
    "Caused by: java.net.ConnectException: Connection refused",
]


def line(t, level, message):
    return f"{t:%Y-%m-%d %H:%M:%S} {level} {message}"


def stack_trace(r):
    frames = r.sample(STACK_FRAMES, r.randint(2, 4))
    if r.random() < 0.5:
        frames.append(r.choice(CAUSES))
    return frames


def normal_entry(r, t, error_weight=None):
    weights = dict(WEIGHTS)
    if error_weight is not None:
        weights["ERROR"] = error_weight
    level = r.choices(list(weights), list(weights.values()))[0]
    if level == "ERROR":
        return level, r.choice(NORMAL_ERRORS)
    return level, {"DEBUG": debug, "INFO": info, "WARNING": warning}[level](r)


def emit_error_with_trace(r, out, t, message, trace_chance):
    out.append(line(t, "ERROR", message))
    if r.random() < trace_chance:
        out.extend(stack_trace(r))


def application_log(r):
    """A normal business day with one small error burst after a deploy."""
    out = []
    t = DAY.replace(hour=8)
    deploy = DAY.replace(hour=12, minute=30)
    deployed = False
    while len(out) < 1500:
        t += timedelta(seconds=r.randint(4, 38))
        if not deployed and t >= deploy:
            deployed = True
            out.append(line(deploy, "INFO", "Deploying version 2.14.0"))
            burst = deploy + timedelta(minutes=2)
            for _ in range(4):
                burst += timedelta(seconds=r.randint(20, 50))
                emit_error_with_trace(r, out, burst, "Order validation failed: missing shipping address", 0.5)
            out.append(line(burst + timedelta(minutes=1), "INFO", "Rolled back to version 2.13.2"))
            t = burst + timedelta(minutes=2)
            continue
        level, message = normal_entry(r, t, error_weight=0.02)
        if level == "ERROR":
            emit_error_with_trace(r, out, t, message, 0.6)
        else:
            out.append(line(t, level, message))

    # Lines the parser must skip, spread through the file.
    junk = [
        "",
        "",
        "2026-02-30 09:00:00 INFO Impossible date",
        "2026-13-01 09:00:00 INFO Month out of range",
        "2026-10-26 25:10:00 INFO Hour out of range",
        "2026-10-26 10:00:00 TRACE Unknown level",
        "2026-10-26 10:00:00 WARN Abbreviated level",
        "2026-10-26 10:00:00 info Lowercase level",
        "2026-10-26 14:3",
        "Exception in thread \"scheduler-1\" java.lang.IllegalStateException: queue closed",
    ]
    for item in junk:
        out.insert(r.randint(1, len(out) - 1), item)
    return out


def incident_log(r):
    """09:00-12:00 with a database outage at 10:15-10:45 and an aftershock."""
    out = []
    t = DAY.replace(hour=9)
    end = DAY.replace(hour=12)
    warn_from = DAY.replace(hour=10, minute=8)
    outage_from = DAY.replace(hour=10, minute=15)
    outage_to = DAY.replace(hour=10, minute=45)
    aftershock = DAY.replace(hour=11, minute=32)
    aftershock_done = False
    restored = False

    outage_errors = ["Database connection refused"] * 6 + [
        "Failed to process payment: timeout",
        "Failed to process payment: timeout",
        "Order service unavailable",
        "Health check failed: database",
    ]

    while t < end:
        if outage_from <= t < outage_to:
            t += timedelta(seconds=r.randint(2, 9))
            roll = r.random()
            if roll < 0.62:
                emit_error_with_trace(r, out, t, r.choice(outage_errors), 0.15)
            elif roll < 0.80:
                out.append(line(t, "WARNING", r.choice([
                    "Connection pool exhausted",
                    f"Retrying database connection (attempt {r.randint(1, 5)})",
                    "Circuit breaker opened for database",
                ])))
            else:
                out.append(line(t, "INFO", info(r)))
            continue

        if t >= outage_to and not restored:
            restored = True
            out.append(line(t, "INFO", "Database connection restored"))
            out.append(line(t + timedelta(seconds=1), "INFO", "Circuit breaker closed for database"))

        if t >= aftershock and not aftershock_done:
            aftershock_done = True
            a = aftershock
            for message in ["Database connection refused", "Database connection refused",
                            "Failed to process payment: timeout"]:
                a += timedelta(seconds=r.randint(30, 70))
                emit_error_with_trace(r, out, a, message, 0.5)
            t = a

        t += timedelta(seconds=r.randint(3, 16))
        if warn_from <= t < outage_from:
            out.append(line(t, "WARNING", r.choice([
                f"Connection pool: {r.randint(40, 49)}/50 in use",
                f"Slow query detected: {r.randint(2500, 6000)}ms",
                f"Response time above 2s for {r.choice(ENDPOINTS)}",
            ])))
            continue
        level, message = normal_entry(r, t, error_weight=0.015)
        if level == "ERROR":
            emit_error_with_trace(r, out, t, message, 0.5)
        else:
            out.append(line(t, level, message))

    for item in ["", "2026-10-26 10:20:00 FATAL Unknown level"]:
        out.insert(r.randint(1, len(out) - 1), item)
    return out


def main():
    out_dir = Path(sys.argv[1])
    for name, build in [("application.log", application_log), ("incident.log", incident_log)]:
        lines = build(random.Random(name))
        assert lines[-1] != "", "a trailing empty line would be dropped by the reader"
        (out_dir / name).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(name, len(lines), "lines")


if __name__ == "__main__":
    main()
