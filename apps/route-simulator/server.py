import json
import math
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


ROAD_FACTOR = 1.18
AVERAGE_SPEED_METERS_PER_SECOND = 35_000 / 3_600


class InvalidParams(ValueError):
    pass


def parse_coordinate(value):
    try:
        longitude_text, latitude_text = value.split(",", 1)
        longitude = float(longitude_text)
        latitude = float(latitude_text)
    except (AttributeError, TypeError, ValueError) as exception:
        raise InvalidParams("invalid coordinate") from exception
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        raise InvalidParams("coordinate out of range")
    return longitude, latitude


def coordinate_text(coordinate):
    return f"{coordinate[0]:.6f},{coordinate[1]:.6f}"


def haversine_meters(origin, destination):
    earth_radius = 6_371_000
    origin_lng, origin_lat = map(math.radians, origin)
    destination_lng, destination_lat = map(math.radians, destination)
    latitude_delta = destination_lat - origin_lat
    longitude_delta = destination_lng - origin_lng
    value = (
        math.sin(latitude_delta / 2) ** 2
        + math.cos(origin_lat)
        * math.cos(destination_lat)
        * math.sin(longitude_delta / 2) ** 2
    )
    return 2 * earth_radius * math.asin(math.sqrt(value))


def route_metrics(coordinates):
    straight_distance = sum(
        haversine_meters(coordinates[index], coordinates[index + 1])
        for index in range(len(coordinates) - 1)
    )
    distance = max(100, math.ceil(straight_distance * ROAD_FACTOR))
    duration = max(60, math.ceil(distance / AVERAGE_SPEED_METERS_PER_SECOND))
    return distance, duration


def success(payload):
    return {"status": "1", "info": "OK", "infocode": "10000", **payload}


def failure():
    return {"status": "0", "info": "INVALID_PARAMS", "infocode": "10001"}


class RouteSimulatorHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/health":
                self.write_json(200, {"status": "UP"})
                return
            query = parse_qs(parsed.query)
            if parsed.path == "/v3/direction/driving":
                self.write_json(200, driving_route(query))
                return
            if parsed.path == "/v3/distance":
                self.write_json(200, distance(query))
                return
            self.write_json(404, {"status": "0", "info": "NOT_FOUND"})
        except InvalidParams:
            self.write_json(400, failure())

    def write_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


def required(query, name):
    values = query.get(name)
    if not values or not values[0].strip():
        raise InvalidParams(f"missing {name}")
    return values[0]


def driving_route(query):
    origin = parse_coordinate(required(query, "origin"))
    destination = parse_coordinate(required(query, "destination"))
    waypoint_text = query.get("waypoints", [""])[0]
    waypoints = [
        parse_coordinate(value)
        for value in waypoint_text.split(";")
        if value.strip()
    ]
    coordinates = [origin, *waypoints, destination]
    distance_meters, duration_seconds = route_metrics(coordinates)
    polyline = ";".join(coordinate_text(coordinate) for coordinate in coordinates)
    return success(
        {
            "route": {
                "origin": coordinate_text(origin),
                "destination": coordinate_text(destination),
                "paths": [
                    {
                        "distance": str(distance_meters),
                        "duration": str(duration_seconds),
                        "steps": [{"polyline": polyline}],
                    }
                ],
            }
        }
    )


def distance(query):
    origin_text = required(query, "origins").split("|", 1)[0]
    origin = parse_coordinate(origin_text)
    destination = parse_coordinate(required(query, "destination"))
    distance_meters, duration_seconds = route_metrics([origin, destination])
    return success(
        {
            "results": [
                {
                    "origin_id": "1",
                    "dest_id": "1",
                    "distance": str(distance_meters),
                    "duration": str(duration_seconds),
                }
            ]
        }
    )


def create_server(host="0.0.0.0", port=8091):
    return ThreadingHTTPServer((host, port), RouteSimulatorHandler)


if __name__ == "__main__":
    server = create_server(port=int(os.environ.get("PORT", "8091")))
    server.serve_forever()
