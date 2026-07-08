from fastapi.testclient import TestClient

from drt_algorithm.main import app


client = TestClient(app)


def test_health_returns_up() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
