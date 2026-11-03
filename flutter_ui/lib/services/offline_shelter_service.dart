import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class ShelterLocation {
  const ShelterLocation({
    required this.name,
    required this.latitude,
    required this.longitude,
  });

  final String name;
  final double latitude;
  final double longitude;
}

class NearbyShelter {
  const NearbyShelter({required this.location, required this.distanceMeters});

  final ShelterLocation location;
  final double distanceMeters;
}

class OfflineShelterService {
  OfflineShelterService._();

  static final OfflineShelterService instance = OfflineShelterService._();
  static const double searchRadiusMeters = 10000;
  static const String assetPath = 'assets/data/shelters_tw.json';

  Future<List<ShelterLocation>>? _shelters;

  Future<List<NearbyShelter>> findNearby({
    required double latitude,
    required double longitude,
  }) async {
    _validateCoordinates(latitude, longitude);
    final shelters = await (_shelters ??= _loadShelters());
    return rankNearby(shelters, latitude: latitude, longitude: longitude);
  }

  static List<NearbyShelter> rankNearby(
    Iterable<ShelterLocation> shelters, {
    required double latitude,
    required double longitude,
  }) {
    _validateCoordinates(latitude, longitude);
    final nearby = <NearbyShelter>[];

    for (final shelter in shelters) {
      final distanceMeters = _distanceMeters(
        latitude,
        longitude,
        shelter.latitude,
        shelter.longitude,
      );
      if (distanceMeters <= searchRadiusMeters) {
        nearby.add(
          NearbyShelter(location: shelter, distanceMeters: distanceMeters),
        );
      }
    }

    nearby.sort((a, b) => a.distanceMeters.compareTo(b.distanceMeters));
    return nearby;
  }

  Future<List<ShelterLocation>> _loadShelters() async {
    final json = await rootBundle.loadString(assetPath);
    return compute(decodeSheltersJson, json);
  }
}

List<ShelterLocation> decodeSheltersJson(String json) {
  final rows = jsonDecode(json);
  if (rows is! List) {
    throw const FormatException('Shelter data must be a list');
  }

  return rows
      .map((row) {
        if (row is! List || row.length != 3) {
          throw const FormatException('Invalid shelter record');
        }
        if (row[0] is! num || row[1] is! num) {
          throw const FormatException('Shelter coordinates must be numeric');
        }
        final latitude = (row[0] as num).toDouble();
        final longitude = (row[1] as num).toDouble();
        if (!latitude.isFinite ||
            latitude < -90 ||
            latitude > 90 ||
            !longitude.isFinite ||
            longitude < -180 ||
            longitude > 180) {
          throw const FormatException('Shelter coordinates are out of range');
        }
        final name = row[2];
        if (name is! String || name.trim().isEmpty) {
          throw const FormatException('Shelter name is missing');
        }
        return ShelterLocation(
          name: name,
          latitude: latitude,
          longitude: longitude,
        );
      })
      .toList(growable: false);
}

double _distanceMeters(
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

double _radians(double degrees) => degrees * math.pi / 180;

void _validateCoordinates(double latitude, double longitude) {
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
