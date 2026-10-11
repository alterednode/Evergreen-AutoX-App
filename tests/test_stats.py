def test_stats_are_public_and_count_devices(client, device):
    assert client.get("/api/stats").json() == {"devices": 0}

    device("alice")
    device("bob", signed_in=False)

    assert client.get("/api/stats").json() == {"devices": 2}


def test_stats_carry_nothing_but_counts(client, device):
    device("alice")

    body = client.get("/api/stats").json()

    assert set(body) == {"devices"}
