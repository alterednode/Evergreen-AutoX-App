"""Import GGLC's result PDFs into gglc_events, for events whose live page GGLC
has taken down.

The PDFs are the live results sheet (an Excel workbook) printed with
Microsoft Print to PDF, so this rebuilds the sheet's cells from the drawing
and hands them to gglc._parse_table like the live page's tables.

Usage: python server/gglc_pdf.py YEAR... | YYYY-MM-DD...
A year imports every PDF GGLC has for it. Each import is saved as final, so
it replaces whatever was stored for that day and the live page is never asked
again (GGLC has reused an old page for test data)."""

import io
import re
import sys
from datetime import date
from itertools import pairwise

import gglc
import pdfplumber

_MONTHS = [
    "jan",
    "feb",
    "mar",
    "apr",
    "may",
    "jun",
    "jul",
    "aug",
    "sep",
    "oct",
    "nov",
    "dec",
]

PDF_URL = "https://gglotus.org/ggautox/{yyyy}results/GGLC_results_{mmddyyyy}.pdf"


def color(r):
    c = r.get("non_stroking_color") or (0,)
    c = tuple(c) * 3 if len(c) == 1 else tuple(c)
    if all(x < 0.05 for x in c):
        return "black"
    if c[0] < 0.3 and 0.4 < c[1] < 0.6 and c[2] < 0.2:
        return "green"
    if all(x > 0.95 for x in c):
        return "white"
    return "highlight"


def text_runs(page):
    # Each cell's string is drawn in one go, overflow included, so a jump in
    # the drawing position starts a new cell's text. Text clipped by a filled
    # neighbour can run right up to that neighbour's text; the neighbour
    # starts one cell padding past the grid line.
    grid = [
        r
        for r in page.rects
        if color(r) == "black" and r["width"] < 2 and r["height"] > 5
    ]

    def starts_cell(c, prev):
        return any(
            r["top"] - 1 <= c["top"] <= r["bottom"]
            and 2.0 <= c["x0"] - r["x0"] <= 3.5
            and prev["x1"] > r["x0"]
            for r in grid
        )

    runs = []
    prev = None
    for c in page.chars:
        new = (
            prev is None
            or abs(c["top"] - prev["top"]) > 1
            # Bold kerning can pull a glyph ~0.7pt into the one before it, so
            # a small step back only starts a cell where a cell's text starts.
            or c["x0"] < prev["x1"] - 1.5
            or (c["x0"] < prev["x1"] - 0.5 and starts_cell(c, prev))
            or c["x0"] > prev["x1"] + 1.0
            or (
                c["text"].strip()
                and prev["text"] != " "
                and not (c["text"].islower() and prev["text"].islower())
                and starts_cell(c, prev)
            )
        )
        if new:
            runs.append(
                {"x0": c["x0"], "top": c["top"], "bottom": c["bottom"], "chars": []}
            )
        runs[-1]["chars"].append(c)
        prev = c
    out = []
    for r in runs:
        text = "".join(c["text"] for c in r["chars"]).strip()
        if not text:
            continue
        visible = [c for c in r["chars"] if c["text"].strip()]
        out.append(
            {
                "x0": visible[0]["x0"],
                "x1": visible[-1]["x1"],
                "mid": (r["top"] + r["bottom"]) / 2,
                "text": text,
                "bold": all("Bold" in c["fontname"] for c in visible),
            }
        )
    return out


def page_rows(page):
    rects = page.rects
    greens = [r for r in rects if color(r) == "green"]
    highlights = [r for r in rects if color(r) == "highlight"]
    lines = [
        r for r in rects if color(r) == "black" and r["width"] < 2 and r["height"] > 5
    ]
    # Rows are the bands between horizontal grid lines, so text that wraps
    # inside a cell stays in its row.
    edges = sorted(
        {
            round(r["top"], 1)
            for r in rects
            if color(r) == "black" and r["height"] < 2 and r["width"] > 5
        }
    )
    edges = [e for i, e in enumerate(edges) if i == 0 or e - edges[i - 1] > 2]
    spans = [(a, b) for a, b in pairwise(edges) if b - a < 60]

    def band_of(mid):
        return next(((a, b) for a, b in spans if a <= mid < b), None)

    # Text wrapped onto two lines is centred on its row, so each line sits
    # off the row's centre and the lower one can cross into the next row.
    # Pair the lines back up and place the pair by its centre.
    def offset(run):
        band = band_of(run["mid"])
        return run["mid"] - (band[0] + band[1]) / 2 if band else None

    runs = text_runs(page)
    offsets = sorted(o for r in runs if (o := offset(r)) is not None)
    usual = offsets[len(offsets) // 2] if offsets else 0
    placed, fragments = [], []
    for run in runs:
        o = offset(run)
        if o is not None and abs(o - usual) > 2.5:
            fragments.append(run)
        else:
            placed.append(run)
    fragments.sort(key=lambda r: r["mid"])

    # Excel only breaks inside a word when the word fills the cell.
    def joiner(top, bottom):
        edge = min((x for x in xs_all if x > top["x0"] + 1), default=None)
        mid_word = top["text"][-1].isalpha() and bottom["text"][0].islower()
        return "" if mid_word and edge is not None and edge - top["x1"] < 6 else " "

    xs_all = sorted({r["x0"] for r in lines})
    while fragments:
        top = fragments.pop(0)
        partner = next(
            (
                f
                for f in fragments
                if abs(f["x0"] - top["x0"]) < 25 and 6 < f["mid"] - top["mid"] < 20
            ),
            None,
        )
        if partner:
            fragments.remove(partner)
            top = {
                **top,
                "mid": (top["mid"] + partner["mid"]) / 2,
                "text": top["text"] + joiner(top, partner) + partner["text"],
                "bold": top["bold"] and partner["bold"],
            }
        else:
            top = {
                **top,
                "mid": min(
                    ((a + b) / 2 + usual for a, b in spans),
                    key=lambda c: abs(c - top["mid"]),
                ),
            }
        placed.append(top)

    bands = {}
    loose = []
    for run in sorted(placed, key=lambda r: (r["mid"], r["x0"])):
        band = band_of(run["mid"])
        if band:
            bands.setdefault(band, []).append(run)
        elif loose and abs(loose[-1]["mid"] - run["mid"]) <= 2:
            loose[-1]["runs"].append(run)
        else:
            loose.append({"mid": run["mid"], "runs": [run]})
    rows = loose + [{"mid": (a + b) / 2, "runs": rs} for (a, b), rs in bands.items()]
    rows.sort(key=lambda r: r["mid"])
    # Excel hides the grid line under text that overflows into an empty
    # cell, so a row's columns are every line seen in its table.
    for row in rows:
        row["xs"] = {
            round(r["x0"], 0)
            for r in lines
            if r["top"] - 1 <= row["mid"] <= r["bottom"] + 1
        }
        row["green"] = any(g["top"] <= row["mid"] <= g["bottom"] for g in greens)
    group = []
    for row in rows + [{"green": True, "xs": set()}]:
        if row["green"] or not row["xs"]:
            union = set().union(*(r["xs"] for r in group)) if group else set()
            for r in group:
                r["xs"] = union
            group = []
        else:
            group.append(row)
    out = []
    for row in rows:
        mid = row["mid"]
        text = " ".join(r["text"] for r in sorted(row["runs"], key=lambda r: r["x0"]))
        if any(g["top"] <= mid <= g["bottom"] for g in greens):
            out.append({"mid": mid, "kind": "class", "text": text})
            continue
        xs = []
        for x in sorted(row["xs"]):
            if not xs or x - xs[-1] > 3:
                xs.append(x)
        if len(xs) < 2:
            out.append({"mid": mid, "kind": "meta", "text": text})
            continue
        cells = []
        for left, right in pairwise(xs):
            inside = [r for r in row["runs"] if left <= r["x0"] + 0.5 < right]
            cx = (left + right) / 2
            best = any(
                h["x0"] <= cx <= h["x1"] and h["top"] <= mid <= h["bottom"]
                for h in highlights
            ) or (inside and all(r["bold"] for r in inside))
            cells.append(
                {
                    "text": " ".join(
                        r["text"]
                        for r in sorted(inside, key=lambda r: (r["mid"], r["x0"]))
                    ),
                    "best": best,
                }
            )
        out.append({"mid": mid, "kind": "cells", "cells": cells})
    return out


def cell(c):
    text = c["text"]
    # GGLC's sheet holds some names as UTF-8 read as Latin-1 ("AndrÃ©").
    try:
        text = text.encode("cp1252").decode("utf-8")
    except UnicodeError:
        pass
    # Some PDFs drop trailing zeros (32.070 prints as 32.07).
    if m := re.fullmatch(r"(\d+\.\d+)(\+\d+)?", text):
        text = f"{float(m.group(1)):.3f}{m.group(2) or ''}"
    return {
        "tag": "td",
        "attrs": {"style": "background-color:lightgreen"} if c["best"] else {},
        "text": text,
    }


def build_table(class_name, rows):
    header = [c["text"] for c in rows[0]["cells"]]
    # Runs must be the last columns, as on the HTML page; the PDFs put Best
    # or Indexed after them.
    is_run = [h.lower().startswith("run") for h in header]
    order = [i for i, r in enumerate(is_run) if not r] + [
        i for i, r in enumerate(is_run) if r
    ]
    table = [[{"tag": "th", "attrs": {}, "text": class_name}]]
    table.append([{"tag": "th", "attrs": {}, "text": header[i]} for i in order])
    for row in rows[1:]:
        cells = row["cells"] + [{"text": "", "best": False}] * (
            len(header) - len(row["cells"])
        )
        out = [cell(cells[i]) for i in order]
        mark_scored_run(out[len(order) - sum(is_run) :])
        table.append(out)
    return table


def mark_scored_run(runs):
    # Some sheets were printed without the highlight (all of May 9 and June 20
    # 2026, the Fun class on Aug 30 2026). GGLC scores the best of the first
    # five runs, which is what the highlight marks when it is there; where it
    # is, it wins, because GGLC's Indexed column follows it even when a faster
    # run exists (Donald, 2026-07-18).
    if any(c["attrs"] for c in runs):
        return
    totals = [
        (r["total"], i)
        for i, c in enumerate(runs[:5])
        if (r := gglc._parse_run(i + 1, c)) and r["total"] is not None
    ]
    if totals:
        runs[min(totals)[1]]["attrs"] = {"style": "background-color:lightgreen"}


_CONTINUATION = re.compile(r"(?i)(run \d+|indexed|best|pax|dnf|dns|[\d.]+(\+\d+)?)?")


def is_right_page(rows):
    # A page holding the right-hand columns of an earlier page starts every
    # row with a run, Indexed or Best cell, never a name.
    firsts = [r["cells"][0]["text"] for r in rows if r["kind"] == "cells"]
    return (
        bool(firsts)
        and all(_CONTINUATION.fullmatch(t) for t in firsts)
        and any(firsts)
        and not any(r["kind"] == "meta" for r in rows)
    )


def convert(path, url, right_pages=None):
    pdf = pdfplumber.open(path)
    pages = [page_rows(p) for p in pdf.pages]
    if right_pages is None:
        right_pages = tuple(
            i + 1 for i, rows in enumerate(pages) if is_right_page(rows)
        )
    if right_pages and len(right_pages) * 2 != len(pages):
        raise ValueError(f"right-hand pages {right_pages} of {len(pages)}")
    lefts = [i for i in range(len(pages)) if i + 1 not in right_pages]
    # Excel prints down, then over: the n-th right-hand page continues the
    # n-th page of the sheet.
    for left, rp in zip(lefts, right_pages):
        right = [r for r in pages[rp - 1] if r["kind"] == "cells"]
        for r in right:
            match = [
                x
                for x in pages[left]
                if x["kind"] == "cells" and abs(x["mid"] - r["mid"]) < 1.5
            ]
            if not match:
                raise ValueError(f"page {rp}: no left row for {r}")
            match[0]["cells"] = match[0]["cells"] + r["cells"]

    title = updated = ""
    tables = []
    current = None
    for i in lefts:
        for row in pages[i]:
            if row["kind"] == "meta":
                if row["text"].startswith("GGLC Autocross Result"):
                    title = row["text"]
                elif m := re.match(r"Updated\s*(.+)", row["text"]):
                    updated = m.group(1).strip()
                else:
                    raise ValueError(f"stray text: {row['text']}")
            elif row["kind"] == "class":
                current = [row["text"], []]
                tables.append(current)
            else:
                if current is None:
                    raise ValueError(f"row outside a table: {row}")
                if all(not c["text"] for c in row["cells"]):
                    continue
                current[1].append(row)

    m = re.search(r"([A-Z][a-z]+) (\d{1,2}), (\d{4})", title)
    month, dd, yyyy = m.groups()
    day = date(int(yyyy), _MONTHS.index(month[:3].lower()) + 1, int(dd))
    return {
        "date": day.isoformat(),
        "url": url,
        "title": title,
        "updated": updated,
        "classes": [
            p
            for name, rows in tables
            if (p := gglc._parse_table(build_table(name, rows))) is not None
        ],
    }


def pdf_url(day: date) -> str:
    return PDF_URL.format(yyyy=day.year, mmddyyyy=day.strftime("%m%d%Y"))


def import_day(day: date) -> str:
    url = pdf_url(day)
    resp = gglc._http.get(url)
    if resp.status_code in (403, 404):
        return "no PDF"
    resp.raise_for_status()
    try:
        event = convert(io.BytesIO(resp.content), url)
    except Exception as e:  # noqa: BLE001
        return f"skipped: {type(e).__name__}: {e}"
    if event["date"] != day.isoformat():
        return f"skipped: the PDF is for {event['date']}"
    if not gglc.has_results(event):
        return "skipped: no runs"
    gglc.save_event(event, "pdf", final=True)
    drivers = sum(len(c["drivers"]) for c in event["classes"])
    return f"saved {len(event['classes'])} classes, {drivers} drivers"


def main(args: list[str]) -> None:
    days = []
    for arg in args:
        if re.fullmatch(r"\d{4}", arg):
            days += gglc._candidates(int(arg))
        else:
            days.append(gglc.parse_date(arg))
    if not days:
        sys.exit(__doc__)
    for day in days:
        result = import_day(day)
        if result != "no PDF" or len(args) == len(days):
            print(day.isoformat(), result)


if __name__ == "__main__":
    main(sys.argv[1:])
