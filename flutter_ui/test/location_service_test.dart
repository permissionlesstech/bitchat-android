import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/location_service.dart';

void main() {
  group('LocationService.validateCoordinates', () {
    test('accepts Taiwan coordinates', () {
      expect(
        () => LocationService.validateCoordinates(25.033964, 121.564472),
        returnsNormally,
      );
    });

    test('rejects invalid latitude', () {
      expect(
        () => LocationService.validateCoordinates(91, 121),
        throwsArgumentError,
      );
    });

    test('rejects invalid longitude', () {
      expect(
        () => LocationService.validateCoordinates(25, double.infinity),
        throwsArgumentError,
      );
    });

    test('stores current location without launching an external app', () {
      final service = LocationService()..updateLocation(25.033964, 121.564472);
      expect(service.getLocation(), {'lat': 25.033964, 'lng': 121.564472});
    });
  });
}
