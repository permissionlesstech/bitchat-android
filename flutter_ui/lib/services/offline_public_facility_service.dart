import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'geo_distance.dart';

enum PublicFacilityType {
  hospital,
  station,
  busStop,
  fireStation,
  policeStation,
}

class PublicFacilityLocation {
  const PublicFacilityLocation({
    required this.id,
    required this.type,
    required this.name,
    required this.latitude,
    required this.longitude,
    this.address = '',
  });

  final String id;
  final PublicFacilityType type;
  final String name;
  final double latitude;
  final double longitude;
  final String address;
}

class NearbyPublicFacility {
  const NearbyPublicFacility({
    required this.location,
    required this.distanceMeters,
  });

  final PublicFacilityLocation location;
  final double distanceMeters;
}

class OfflinePublicFacilityService {
  OfflinePublicFacilityService._();

  static final OfflinePublicFacilityService instance =
      OfflinePublicFacilityService._();
  static const double searchRadiusMeters = 10000;
  static const Set<PublicFacilityType> emergencySearchTypes = {
    PublicFacilityType.hospital,
    PublicFacilityType.fireStation,
    PublicFacilityType.policeStation,
  };
  static const String assetPath = 'assets/data/public_facilities_tw.json';
  static const String busStopAssetPath = 'assets/data/bus_stops_tw.json';

  Future<List<PublicFacilityLocation>>? _facilities;

  Future<List<NearbyPublicFacility>> findNearby({
    required double latitude,
    required double longitude,
  }) async {
    validateCoordinates(latitude, longitude);
    final facilities = await (_facilities ??= _loadFacilities());
    return rankNearby(facilities, latitude: latitude, longitude: longitude);
  }

  Future<Set<PublicFacilityType>> get availableTypes async {
    final facilities = await (_facilities ??= _loadFacilities());
    return facilities.map((facility) => facility.type).toSet();
  }

  static List<NearbyPublicFacility> rankNearby(
    Iterable<PublicFacilityLocation> facilities, {
    required double latitude,
    required double longitude,
  }) {
    validateCoordinates(latitude, longitude);
    final nearby = <NearbyPublicFacility>[];

    for (final facility in facilities) {
      final distanceMeters = distanceMetersBetween(
        latitude,
        longitude,
        facility.latitude,
        facility.longitude,
      );
      if (distanceMeters <= searchRadiusMeters) {
        nearby.add(
          NearbyPublicFacility(
            location: facility,
            distanceMeters: distanceMeters,
          ),
        );
      }
    }

    nearby.sort((a, b) => a.distanceMeters.compareTo(b.distanceMeters));
    return nearby;
  }

  static List<NearbyPublicFacility> searchEmergencyFacilities(
    Iterable<NearbyPublicFacility> facilities, {
    required String query,
  }) {
    final eligible = facilities
        .where(
          (facility) => emergencySearchTypes.contains(facility.location.type),
        )
        .toList(growable: false);
    final normalizedQuery = query.trim().toLowerCase();
    if (normalizedQuery.isEmpty) return eligible;

    final nameMatches = eligible
        .where((facility) {
          final location = facility.location;
          return location.name.toLowerCase().contains(normalizedQuery) ||
              location.address.toLowerCase().contains(normalizedQuery);
        })
        .toList(growable: false);
    if (nameMatches.isNotEmpty) return nameMatches;

    final matchingTypes = <PublicFacilityType>{};
    for (final type in emergencySearchTypes) {
      if (_searchAliases[type]!.any(
        (alias) =>
            alias.contains(normalizedQuery) || normalizedQuery.contains(alias),
      )) {
        matchingTypes.add(type);
      }
    }
    return eligible
        .where((facility) => matchingTypes.contains(facility.location.type))
        .toList(growable: false);
  }

  Future<List<PublicFacilityLocation>> _loadFacilities() async {
    final jsonAssets = await Future.wait([
      rootBundle.loadString(assetPath),
      rootBundle.loadString(busStopAssetPath),
    ]);
    return compute(decodePublicFacilityJsonAssets, jsonAssets);
  }
}

const Map<PublicFacilityType, List<String>> _searchAliases = {
  PublicFacilityType.hospital: ['醫院', '醫療', 'hospital'],
  PublicFacilityType.fireStation: ['消防', '消防局', '消防隊', 'fire'],
  PublicFacilityType.policeStation: ['警察', '警察局', '派出所', 'police'],
};

List<PublicFacilityLocation> decodePublicFacilityJsonAssets(
  List<String> jsonAssets,
) => jsonAssets.expand(decodePublicFacilitiesJson).toList(growable: false);

List<PublicFacilityLocation> decodePublicFacilitiesJson(String json) {
  final rows = jsonDecode(json);
  if (rows is! List) {
    throw const FormatException('Public facility data must be a list');
  }

  return rows
      .map((row) {
        if (row is! List || row.length != 6) {
          throw const FormatException('Invalid public facility record');
        }
        final id = row[0];
        final typeName = row[1];
        if (id is! String || id.trim().isEmpty || typeName is! String) {
          throw const FormatException('Public facility identity is missing');
        }
        final type = _facilityTypeFromName(typeName);
        final latitude = row[2];
        final longitude = row[3];
        if (latitude is! num || longitude is! num) {
          throw const FormatException(
            'Public facility coordinates must be numeric',
          );
        }
        final latitudeValue = latitude.toDouble();
        final longitudeValue = longitude.toDouble();
        try {
          validateCoordinates(latitudeValue, longitudeValue);
        } on ArgumentError {
          throw const FormatException(
            'Public facility coordinates are out of range',
          );
        }
        final name = row[4];
        final address = row[5];
        if (name is! String || name.trim().isEmpty || address is! String) {
          throw const FormatException(
            'Public facility name or address is invalid',
          );
        }
        return PublicFacilityLocation(
          id: id,
          type: type,
          name: name,
          latitude: latitudeValue,
          longitude: longitudeValue,
          address: address,
        );
      })
      .toList(growable: false);
}

PublicFacilityType _facilityTypeFromName(String name) {
  for (final type in PublicFacilityType.values) {
    if (type.name == name) return type;
  }
  throw FormatException('Unknown public facility type: $name');
}
