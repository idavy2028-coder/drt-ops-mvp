import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

from server import create_server


class RouteSimulatorContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.server = create_server("127.0.0.1", 0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def test_health_endpoint(self):
        status, payload = self.get("/health")

        self.assertEqual(200, status)
        self.assertEqual({"status": "UP"}, payload)

    def test_driving_route_returns_amap_compatible_path(self):
        status, payload = self.get(
            "/v3/direction/driving"
            "?origin=105.240000,35.210000"
            "&destination=105.260000,35.230000"
            "&waypoints=105.250000,35.220000"
            "&key=local-simulator"
        )

        self.assertEqual(200, status)
        self.assertEqual("1", payload["status"])
        path = payload["route"]["paths"][0]
        self.assertGreater(int(path["distance"]), 0)
        self.assertGreater(int(path["duration"]), 0)
        self.assertEqual(
            "105.240000,35.210000;105.250000,35.220000;105.260000,35.230000",
            path["steps"][0]["polyline"],
        )

    def test_distance_returns_amap_compatible_result(self):
        status, payload = self.get(
            "/v3/distance"
            "?origins=105.240000,35.210000"
            "&destination=105.260000,35.230000"
            "&type=1"
            "&key=local-simulator"
        )

        self.assertEqual(200, status)
        self.assertEqual("1", payload["status"])
        result = payload["results"][0]
        self.assertGreater(int(result["distance"]), 0)
        self.assertGreater(int(result["duration"]), 0)

    def test_same_request_is_deterministic(self):
        path = (
            "/v3/direction/driving"
            "?origin=105.240000,35.210000"
            "&destination=105.260000,35.230000"
        )

        first = self.get(path)
        second = self.get(path)

        self.assertEqual(first, second)

    def test_invalid_coordinate_returns_amap_failure_payload(self):
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.get(
                "/v3/direction/driving"
                "?origin=invalid"
                "&destination=105.260000,35.230000"
            )

        self.assertEqual(400, raised.exception.code)
        payload = json.loads(raised.exception.read().decode("utf-8"))
        self.assertEqual("0", payload["status"])
        self.assertEqual("INVALID_PARAMS", payload["info"])

    def get(self, path):
        with urllib.request.urlopen(self.base_url + path, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
