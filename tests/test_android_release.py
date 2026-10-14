import json

import db

APK = b"PK\x03\x04fake apk"


def publish(name: str = "AutoX-Live-1.5-1.apk", write_apk: bool = True):
    directory = db.android_dir()
    directory.mkdir(parents=True, exist_ok=True)
    if write_apk:
        (directory / name).write_bytes(APK)
    (directory / "release.json").write_text(
        json.dumps(
            {
                "version_name": "1.5",
                "version_code": 10501,
                "sha256": "ab" * 32,
                "size": len(APK),
                "file_name": name,
            }
        )
    )


def test_no_release_is_404(client):
    assert client.get("/api/android/release").status_code == 404
    assert client.get("/android").status_code == 404


def test_release_without_its_apk_is_404(client):
    publish(write_apk=False)
    assert client.get("/api/android/release").status_code == 404
    assert client.get("/android").status_code == 404


def test_release_is_public(client):
    publish()
    response = client.get("/api/android/release")
    assert response.status_code == 200
    assert response.json() == {
        "version_name": "1.5",
        "version_code": 10501,
        "sha256": "ab" * 32,
        "size": len(APK),
        "file_name": "AutoX-Live-1.5-1.apk",
        "download_url": "/android",
    }


def test_apk_download(client):
    publish()
    response = client.get("/android")
    assert response.status_code == 200
    assert response.content == APK
    assert response.headers["content-type"] == "application/vnd.android.package-archive"
    assert 'filename="AutoX-Live-1.5.apk"' in response.headers["content-disposition"]


def test_file_name_cannot_leave_the_directory(client):
    publish()
    (db.android_dir().parent / "secret.apk").write_bytes(b"nope")
    release = json.loads((db.android_dir() / "release.json").read_text())
    release["file_name"] = "../secret.apk"
    (db.android_dir() / "release.json").write_text(json.dumps(release))
    assert client.get("/android").status_code == 404
