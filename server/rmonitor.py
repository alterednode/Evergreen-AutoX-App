"""Turns an RMonitor timing feed into Speedhive-shaped results.

RMonitor is the line protocol Orbits sends to scoreboards and Race Monitor
relays to its live timing: one comma-separated command per line, strings in
double quotes, e.g. `$G,3,"1234BE",14,"01:12:47.872"`. Competitors are keyed
by registration number, which is not the car number.

`results()` and `laps()` mirror the rows the apps already read from Speedhive
(`/api/sessions/{id}/results` and `/laps`), so a live session could be shown
with the same views."""

import csv


def _seconds(text: str) -> float | None:
    if not text:
        return None
    try:
        seconds = 0.0
        for part in text.split(":"):
            seconds = seconds * 60 + float(part)
    except ValueError:
        return None
    return seconds


def _speedhive_time(text: str) -> str | None:
    """`00:01:01.809` -> `01:01.809`, the form Speedhive answers with."""
    seconds = _seconds(text)
    if not seconds:
        return None
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{int(hours)}:{int(minutes):02d}:{secs:06.3f}"
    return f"{int(minutes):02d}:{secs:06.3f}"


class Competitor:
    def __init__(self, reg: str):
        self.reg = reg
        self.number = ""
        self.name = ""
        self.class_id = ""
        self.position: int | None = None
        self.laps_done = 0
        self.total_time = ""
        self.best_position: int | None = None
        self.best_lap: int | None = None
        self.best_time = ""
        self.last_time = ""
        # lap number -> lap time text
        self.lap_times: dict[int, str] = {}


class Session:
    def __init__(self):
        self.reset()

    def reset(self) -> None:
        self.competitors: dict[str, Competitor] = {}
        self.classes: dict[str, str] = {}
        self.session_id = ""
        self.session_name = ""
        self.sort_mode = ""
        self.flag = ""
        self.time_of_day = ""
        self.track = ""

    def feed(self, text: str) -> None:
        """Takes any number of lines, as a websocket frame may hold several."""
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("$"):
                self._command(next(csv.reader([line])))

    def _competitor(self, reg: str) -> Competitor:
        if reg not in self.competitors:
            self.competitors[reg] = Competitor(reg)
        return self.competitors[reg]

    def _command(self, f: list[str]) -> None:
        f = [part.strip() for part in f]
        kind, args = f[0].upper(), f[1:]

        def arg(i: int) -> str:
            return args[i] if i < len(args) else ""

        def number(i: int) -> int | None:
            try:
                return int(arg(i))
            except ValueError:
                return None

        if kind == "$I":
            self.reset()
        elif kind == "$A":
            c = self._competitor(arg(0))
            c.number = arg(1)
            c.name = " ".join(part for part in (arg(3), arg(4)) if part)
            c.class_id = arg(6)
        elif kind == "$COMP":
            c = self._competitor(arg(0))
            c.number = arg(1)
            c.class_id = arg(2)
            c.name = " ".join(part for part in (arg(3), arg(4)) if part)
        elif kind == "$B":
            self.session_id, self.session_name = arg(0), arg(1)
        elif kind == "$C":
            self.classes[arg(0)] = arg(1)
        elif kind == "$E":
            if arg(0).upper() == "TRACKNAME":
                self.track = arg(1)
        elif kind == "$F":
            self.time_of_day, self.flag = arg(2), arg(4)
        elif kind == "$G":
            c = self._competitor(arg(1))
            c.position = number(0)
            c.laps_done = number(2) or c.laps_done
            c.total_time = arg(3)
        elif kind == "$H":
            c = self._competitor(arg(1))
            c.best_position = number(0)
            c.best_lap = number(2)
            c.best_time = arg(3)
        elif kind == "$J":
            c = self._competitor(arg(0))
            c.last_time = arg(1)
            # $J follows the $G that counted the lap.
            if arg(1) and c.laps_done:
                c.lap_times.setdefault(c.laps_done, arg(1))
        elif kind == "$RMHL":
            lap = number(1)
            if lap is not None and arg(3):
                self._competitor(arg(0)).lap_times[lap] = arg(3)
        elif kind == "$RMS":
            self.sort_mode = arg(0).lower()

    def _ranked(self) -> list[Competitor]:
        # AutoX sessions run in qualifying mode, ranked by best run.
        by_best = self.sort_mode.startswith("q")

        def key(c: Competitor):
            first, second = (
                (c.best_position, c.position)
                if by_best
                else (c.position, c.best_position)
            )
            position = first if first else second
            return (position is None, position or 0, c.number)

        return sorted((c for c in self.competitors.values() if c.number), key=key)

    def results(self) -> list[dict]:
        rows = []
        class_counts: dict[str, int] = {}
        for position, c in enumerate(self._ranked(), start=1):
            class_name = self.classes.get(c.class_id, c.class_id) or None
            class_counts[class_name] = class_counts.get(class_name, 0) + 1
            rows.append(
                {
                    "position": position,
                    "name": c.name or None,
                    "startNumber": c.number,
                    "resultClass": class_name,
                    "bestTime": _speedhive_time(c.best_time),
                    "bestLap": c.best_lap,
                    "numberOfLaps": max(c.laps_done, len(c.lap_times)),
                    "positionInClass": class_counts[class_name],
                    "lastLapTime": _speedhive_time(c.last_time),
                    "status": None,
                }
            )
        return rows

    def laps(self) -> list[dict]:
        return [
            {
                "position": position,
                "laps": [
                    {
                        "lap": lap,
                        "lapTime": _speedhive_time(text),
                        "timeOfDay": None,
                        "speed": None,
                    }
                    for lap, text in sorted(c.lap_times.items())
                ],
            }
            for position, c in enumerate(self._ranked(), start=1)
        ]
