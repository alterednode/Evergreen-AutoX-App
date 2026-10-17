import time
from threading import Event, Lock

MAX_ENTRIES = 500
# How long a failure is remembered before the next caller asks upstream again,
# so a dead upstream is asked once per interval, not once per poll.
FAILURE_TTL = 10
# The oldest answer still served when upstream fails; past this the error shows.
STALE_LIMIT = 15 * 60

# key -> (expires, value, error, fetched_at). An entry with an error has no value.
_entries: dict = {}
# key -> Event set when the one fetch in flight for that key finishes.
_inflight: dict = {}
# Guards the two dicts only; no fetch ever runs under it.
_lock = Lock()


def cached(key, ttl: float, fetch):
    while True:
        with _lock:
            now = time.monotonic()
            hit = _entries.get(key)
            if hit is not None and hit[0] > now:
                if hit[2] is not None:
                    raise hit[2]
                return hit[1]
            pending = _inflight.get(key)
            if pending is None:
                pending = _inflight[key] = Event()
                break
            # Someone is already asking upstream; while they wait on it,
            # everyone else gets the last answer instead of queueing behind
            # a hung request.
            if _stale(hit, now):
                return hit[1]
        pending.wait()

    try:
        value = fetch()
    except Exception as error:
        with _lock:
            now = time.monotonic()
            if _stale(hit, now):
                _entries[key] = (now + FAILURE_TTL, hit[1], None, hit[3])
                return hit[1]
            _store(key, (now + FAILURE_TTL, None, error, now), now)
        raise
    else:
        with _lock:
            now = time.monotonic()
            _store(key, (now + ttl, value, None, now), now)
        return value
    finally:
        with _lock:
            _inflight.pop(key, None)
        pending.set()


def _stale(hit, now) -> bool:
    return hit is not None and hit[2] is None and now - hit[3] <= STALE_LIMIT


def _store(key, entry, now) -> None:
    if len(_entries) >= MAX_ENTRIES:
        for stale in [k for k, e in _entries.items() if e[0] <= now]:
            _entries.pop(stale, None)
    _entries[key] = entry


def clear() -> None:
    with _lock:
        _entries.clear()
