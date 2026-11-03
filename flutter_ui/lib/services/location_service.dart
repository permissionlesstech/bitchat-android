import 'dart:async';

import 'package:geolocator/geolocator.dart';

class LocationService {
  double latitude = 0;
  double longitude = 0;

  Future<Position> getCurrentPosition() async {
    if (!await Geolocator.isLocationServiceEnabled()) {
      throw const LocationServiceException('請先開啟裝置定位服務，再使用離線地圖。');
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.unableToDetermine) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied) {
      throw const LocationServiceException('需要定位權限才能將離線地圖移至目前位置。');
    }
    if (permission == LocationPermission.deniedForever) {
      throw const LocationServiceException('定位權限已永久拒絕，請至系統設定允許定位。');
    }

    try {
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 15),
        ),
      );
      updateLocation(position.latitude, position.longitude);
      return position;
    } on TimeoutException {
      return await _fallbackToLastKnownPosition();
    } catch (_) {
      return await _fallbackToLastKnownPosition();
    }
  }

  Future<Position> _fallbackToLastKnownPosition() async {
    final lastKnown = await Geolocator.getLastKnownPosition();
    if (lastKnown == null) {
      throw const LocationServiceException('無法取得目前定位。請確認定位服務已開啟並重試。');
    }
    updateLocation(lastKnown.latitude, lastKnown.longitude);
    return lastKnown;
  }

  void updateLocation(double lat, double lng) {
    latitude = lat;
    longitude = lng;
  }

  static void validateCoordinates(double latitude, double longitude) {
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

  Map<String, double> getLocation() {
    return {"lat": latitude, "lng": longitude};
  }
}

class LocationServiceException implements Exception {
  const LocationServiceException(this.message);

  final String message;

  @override
  String toString() => message;
}
