import 'dart:math' as math;

double distanceMetersBetween(
  double startLatitude,
  double startLongitude,
  double endLatitude,
  double endLongitude,
) {
  const earthRadiusMeters = 6371000.0;
  final latitudeDelta = _radians(endLatitude - startLatitude);
  final longitudeDelta = _radians(endLongitude - startLongitude);
  final haversine =
      math.pow(math.sin(latitudeDelta / 2), 2) +
      math.cos(_radians(startLatitude)) *
          math.cos(_radians(endLatitude)) *
          math.pow(math.sin(longitudeDelta / 2), 2);
  return 2 * earthRadiusMeters * math.asin(math.sqrt(haversine));
}

void validateCoordinates(double latitude, double longitude) {
  if (!latitude.isFinite || latitude < -90 || latitude > 90) {
    throw ArgumentError.value(
      latitude,
      'latitude',
      'Must be between -90 and 90',
    );
  }
  if (!longitude.isFinite || longitude < -180 || longitude > 180) {
    throw ArgumentError.value(
      longitude,
      'longitude',
      'Must be between -180 and 180',
    );
  }
}

double _radians(double degrees) => degrees * math.pi / 180;
