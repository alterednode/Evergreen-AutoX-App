import base64

import db
import pytest
from conftest import ADMIN

ENTRY = {"vehicle": "Miata", "year": 1999, "hp": 140, "zero_to_60_seconds": 8}
RUN = {"driver": "Driver", "vehicle": "1999 Miata", "hp": 140, "time": 60}
PROOF = base64.b64encode(b"\xff\xd8\xff" + b"fixture").decode()


@pytest.mark.parametrize("kind", ["run", "acceleration"])
def test_provenance_and_review_edits(client, device, kind):
    owner = device("owner")
    if kind == "run":
        course = client.post(
            "/api/leaderboard/courses", json={"name": "Track"}, auth=ADMIN
        ).json()["id"]
        create = f"/api/leaderboard/courses/{course}/runs"
        payload = RUN
        submission = {"run": payload, "course_id": course}
        edit = "/api/leaderboard/runs/"
    else:
        create, edit, payload = "/api/acceleration", "/api/acceleration/", ENTRY
        submission = {"acceleration": payload}
    for source in ("photo", "approved", "manual", "server"):
        assert (
            client.post(
                create, json={**payload, "source": source}, headers=owner
            ).status_code
            == 403
        )
    imported = client.post(
        create,
        json={**payload, "source": "trackaddict", "status": "approved"},
        headers=owner,
    ).json()
    assert imported["source"] == "trackaddict"
    assert (
        client.patch(
            edit + str(imported["id"]), json={"source": "photo"}, headers=owner
        ).status_code
        == 403
    )
    queued = client.post(
        "/api/submissions", json={**submission, "proof": PROOF}, headers=owner
    ).json()
    url = f"/api/admin/submissions/{queued['id']}/approve"
    approved = client.post(url, auth=ADMIN)
    assert approved.status_code == 200
    reviewed_id = approved.json()["result_id"]
    assert client.post(url, auth=ADMIN).status_code == 409
    for change in ({"hp": 999}, {"source": "trackaddict"}):
        assert (
            client.patch(
                edit + str(reviewed_id), json=change, headers=owner
            ).status_code
            == 403
        )
    assert (
        client.patch(edit + str(reviewed_id), json={"hp": 150}, auth=ADMIN).status_code
        == 200
    )


def test_missing_evidence_cannot_be_approved(client, device):
    queued = client.post(
        "/api/submissions",
        json={"acceleration": ENTRY, "proof": PROOF},
        headers=device("owner"),
    ).json()
    for file in db.proof_dir().iterdir():
        file.unlink()
    assert (
        client.post(
            f"/api/admin/submissions/{queued['id']}/approve", auth=ADMIN
        ).status_code
        == 409
    )
    assert client.get("/api/acceleration").json() == []
