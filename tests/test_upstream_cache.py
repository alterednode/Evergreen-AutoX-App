import threading
from types import SimpleNamespace

import app
import cache
import gglc
import pytest


@pytest.fixture(autouse=True)
def empty_cache():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def clock(monkeypatch):
    now = [1000.0]
    monkeypatch.setattr(cache.time, "monotonic", lambda: now[0])
    return now


def test_results_are_fetched_once_per_ttl(client, clock, monkeypatch):
    calls = []

    def get_results(session_id):
        calls.append(session_id)
        return [{"position": 1, "name": f"fetch {len(calls)}"}]

    monkeypatch.setattr(app, "client", SimpleNamespace(get_results=get_results))

    first = client.get("/api/sessions/7/results").json()
    assert client.get("/api/sessions/7/results").json() == first
    assert client.get("/api/sessions/7/drivers").json()[0]["name"] == "fetch 1"
    assert calls == [7]

    client.get("/api/sessions/8/results")
    assert calls == [7, 8]

    clock[0] += app.LIVE_TTL + 1
    assert client.get("/api/sessions/7/results").json()[0]["name"] == "fetch 3"


def test_a_failure_is_retried_after_a_short_wait(client, clock, monkeypatch):
    outcomes = [RuntimeError("upstream down"), [{"position": 1}]]

    def get_results(session_id):
        outcome = outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    monkeypatch.setattr(app, "client", SimpleNamespace(get_results=get_results))

    with pytest.raises(RuntimeError):
        client.get("/api/sessions/7/results")
    # Remembered briefly, so a dead upstream is not asked on every poll.
    with pytest.raises(RuntimeError):
        client.get("/api/sessions/7/results")
    clock[0] += cache.FAILURE_TTL + 1
    assert client.get("/api/sessions/7/results").json() == [{"position": 1}]


def test_the_last_answer_is_served_while_upstream_fails(clock):
    calls = []

    def failing():
        calls.append(1)
        raise RuntimeError("upstream down")

    cache.cached("k", 10, lambda: "old")
    clock[0] += 11
    assert cache.cached("k", 10, failing) == "old"
    assert cache.cached("k", 10, failing) == "old"
    assert len(calls) == 1
    clock[0] += cache.STALE_LIMIT
    with pytest.raises(RuntimeError):
        cache.cached("k", 10, failing)


def test_callers_do_not_queue_behind_a_hung_fetch(clock):
    cache.cached("k", 10, lambda: "old")
    clock[0] += 11
    started, release = threading.Event(), threading.Event()

    def hung():
        started.set()
        release.wait(5)
        return "new"

    worker = threading.Thread(target=lambda: cache.cached("k", 10, hung))
    worker.start()
    started.wait(5)
    assert cache.cached("k", 10, lambda: pytest.fail("fetched twice")) == "old"
    assert cache.cached("other", 10, lambda: "x") == "x"
    release.set()
    worker.join(5)
    assert cache.cached("k", 10, lambda: "unused") == "new"


def test_gglc_event_is_cached_and_a_missing_one_still_404s(client, monkeypatch):
    calls = []

    def fetch_event(day):
        calls.append(day)
        if day.day == 2:
            return None
        return {"date": day.isoformat(), "classes": []}

    monkeypatch.setattr(gglc, "fetch_event", fetch_event)

    assert client.get("/api/gglc/events/2026-10-03").json()["date"] == "2026-10-03"
    assert client.get("/api/gglc/events/20261003").status_code == 200
    assert len(calls) == 1
    assert client.get("/api/gglc/events/2026-10-02").status_code == 404


def test_expired_entries_are_dropped_when_full(clock, monkeypatch):
    monkeypatch.setattr(cache, "MAX_ENTRIES", 3)
    for key in range(3):
        cache.cached(key, 10, lambda: None)
    clock[0] += 11
    cache.cached("new", 10, lambda: "value")
    assert list(cache._entries) == ["new"]
