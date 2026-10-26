import rmonitor

# Shaped like the 10/25 Evergreen session on Race Monitor (race 171162), in
# RMonitor's own syntax. Not a recording: replace it once one exists.
FEED = """\
$I,"16:36:08.000","25 Oct 26"
$B,8789638,"AutoX - 10/25 Powered by 425 Motorsports"
$RMS,"qualify"
$C,1,"AutoX - RWD"
$C,2,"AutoX - AWD"
$A,"R7","7",1001,"Red","MR2","USA",1
$COMP,"R12","12",2,"Blue","WRX","USA",""
$A,"R84","84",1003,"","","",1
$F,9999,"00:00:00","13:20:00","05:28:00","Green "
$G,1,"R12",24,"00:30:01.100"
$J,"R12","00:01:02.052","00:30:01.100"
$H,1,"R12",24,"00:01:02.052"
$G,2,"R7",25,"00:31:00.000"
$J,"R7","00:01:01.809","00:31:00.000"
$H,1,"R7",25,"00:01:01.809"
$H,2,"R12",24,"00:01:02.052"
$RMHL,"R7","24","2","00:01:02.300","Green","00:29:58.191"
"""


def test_results_rank_by_best_run_in_qualifying():
    session = rmonitor.Session()
    session.feed(FEED)
    rows = session.results()
    assert session.session_name == "AutoX - 10/25 Powered by 425 Motorsports"
    assert session.flag == "Green"
    assert [r["startNumber"] for r in rows[:2]] == ["7", "12"]
    first = rows[0]
    assert first["name"] == "Red MR2"
    assert first["resultClass"] == "AutoX - RWD"
    assert first["bestTime"] == "01:01.809"
    assert first["bestLap"] == 25
    assert first["lastLapTime"] == "01:01.809"
    assert first["positionInClass"] == 1
    assert rows[1]["resultClass"] == "AutoX - AWD"


def test_car_without_a_time_sorts_last():
    session = rmonitor.Session()
    session.feed(FEED)
    last = session.results()[-1]
    assert last["startNumber"] == "84"
    assert last["name"] is None
    assert last["bestTime"] is None
    assert last["positionInClass"] == 2


def test_laps_come_from_lap_history_and_last_lap():
    session = rmonitor.Session()
    session.feed(FEED)
    laps = session.laps()[0]
    assert laps["position"] == 1
    assert [(lap["lap"], lap["lapTime"]) for lap in laps["laps"]] == [
        (24, "01:02.300"),
        (25, "01:01.809"),
    ]


def test_init_clears_the_session():
    session = rmonitor.Session()
    session.feed(FEED)
    session.feed('$I,"17:00:00.000","25 Oct 26"')
    assert session.results() == []


def test_race_mode_ranks_by_running_position():
    session = rmonitor.Session()
    session.feed(FEED.replace('$RMS,"qualify"', '$RMS,"race"'))
    assert [r["startNumber"] for r in session.results()[:2]] == ["12", "7"]
