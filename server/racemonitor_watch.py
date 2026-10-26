"""Dev-only: watch a Race Monitor race live and print its results.

Race Monitor's terms only allow its API for apps it has authorized, and this
reads the feed its public web widget uses, so it is an experiment to run by
hand until Race Monitor issues an API key. Keep it out of app.py, the
notification watcher and the apps.

Usage:
  python server/racemonitor_watch.py RACE_ID [--record FILE]
  python server/racemonitor_watch.py --replay FILE

Live mode does what the widget does: asks WebRaceList every 30 seconds until
the race is receiving data, then joins its websocket and prints the standings
every few seconds. --record saves every frame (tab-separated receive time and
the frame, newlines escaped) so a session can be replayed through rmonitor.py
without a live event. One connection, like one browser tab."""

import argparse
import json
import os
import sys
import time
from datetime import datetime

import httpx
from rmonitor import Session
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect

RACE_LIST = "https://api.race-monitor.com/Info/WebRaceList"
ORIGIN = "https://api.race-monitor.com"
LIST_INTERVAL = 30
PRINT_INTERVAL = 5


def _print(session: Session) -> None:
    rows = session.results()
    print(
        f"\n{datetime.now():%H:%M:%S}  {session.session_name or '(no session yet)'}"
        f"  flag={session.flag or '-'}  sort={session.sort_mode or '-'}"
        f"  cars={len(rows)}"
    )
    for row in rows[:40]:
        print(
            f"{row['position']:>3}  #{row['startNumber']:<6} {(row['name'] or '')[:24]:<24}"
            f" {(row['resultClass'] or '')[:14]:<14} best {row['bestTime'] or '-':>10}"
            f"  last {row['lastLapTime'] or '-':>10}  runs {row['numberOfLaps']}"
        )


def _live_race(race_id: int) -> dict | None:
    response = httpx.get(
        RACE_LIST,
        params={"accountID": "", "seriesID": "", "raceID": race_id},
        timeout=10,
    )
    response.raise_for_status()
    body = response.json()
    race = next(
        (r for r in body.get("CurrentRaces") or [] if r.get("ID") == race_id), None
    )
    if race is None:
        print(f"Race {race_id} is not in Race Monitor's current races.")
        return None
    if not race.get("ReceivingData"):
        return {}
    return {
        "host": body["LiveTimingHost"],
        "instance": race["Instance"],
        "token": body["LiveTimingToken"],
    }


def watch(race_id: int, record) -> None:
    session = Session()
    while True:
        live = _live_race(race_id)
        if live is None:
            return
        if not live:
            print(
                f"{datetime.now():%H:%M:%S}  race {race_id} is not receiving data yet"
            )
            time.sleep(LIST_INTERVAL)
            continue
        url = f"wss://{live['host']}/instance/{live['instance']}/{live['token']}"
        printed = 0.0
        try:
            with connect(url, origin=ORIGIN, open_timeout=10) as socket:
                socket.send(f"$JOIN,{live['instance']},{live['token']}")
                print(f"Joined {live['host']} instance {live['instance']}")
                for frame in socket:
                    if isinstance(frame, bytes):
                        frame = frame.decode("utf-8", "replace")
                    if record:
                        escaped = frame.replace("\\", "\\\\").replace("\n", "\\n")
                        record.write(f"{time.time():.3f}\t{escaped}\n")
                        record.flush()
                    session.feed(frame)
                    if time.monotonic() - printed >= PRINT_INTERVAL:
                        _print(session)
                        printed = time.monotonic()
        except (ConnectionClosed, OSError) as error:
            print(f"Connection dropped ({error}); checking the race again")
            time.sleep(5)


def replay(path: str) -> None:
    session = Session()
    commands: dict[str, int] = {}
    with open(path) as lines:
        for line in lines:
            _, _, frame = line.rstrip("\n").partition("\t")
            frame = frame.replace("\\n", "\n").replace("\\\\", "\\")
            for command in frame.splitlines():
                kind = command.split(",", 1)[0]
                commands[kind] = commands.get(kind, 0) + 1
            session.feed(frame)
    _print(session)
    print("\ncommands seen:", json.dumps(dict(sorted(commands.items()))))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("race_id", type=int, nargs="?")
    parser.add_argument("--record", help="save every frame to this file")
    parser.add_argument("--replay", help="replay a file saved with --record")
    args = parser.parse_args()
    if args.replay:
        replay(args.replay)
    elif args.race_id:
        with open(args.record or os.devnull, "a") as record:
            try:
                watch(args.race_id, record if args.record else None)
            except KeyboardInterrupt:
                pass
    else:
        parser.print_usage()
        sys.exit(2)


if __name__ == "__main__":
    main()
