import json
from pathlib import Path

import notify

# The same cases run against the Android port (RunFilterParityTest.kt); the iOS
# app has the third copy of this logic.
CASES = json.loads(
    (Path(__file__).parent / "data" / "run_filter_cases.json").read_text()
)


def _upstream(laps: list) -> notify.Upstream:
    return notify.Upstream(
        sessions=lambda _event: [{"id": 1}],
        results=lambda _session: [
            {"position": 1, "name": "Driver", "startNumber": "1"}
        ],
        laps=lambda _session: [
            {
                "position": 1,
                "laps": [{"lap": i + 1, "lapTime": t} for i, t in enumerate(laps)],
            }
        ],
        gglc_event=lambda _day: None,
        events=lambda _org: [],
    )


def test_speedhive_run_filter():
    for case in CASES["speedhive"]:
        (car,) = notify._speedhive_cars(_upstream(case["laps"]), 1)
        assert [run.seconds for run in car.runs] == case["runs"], case["about"]


def test_gglc_ranking():
    cars = notify._gglc_cars(CASES["gglc"]["event"])
    ranked = [
        {
            "position": car.position,
            "number": car.number,
            "name": car.name,
            "class": car.car_class,
            "class_position": car.class_position,
            "best": car.best,
        }
        for car in cars
    ]
    assert ranked == CASES["gglc"]["ranked"]
