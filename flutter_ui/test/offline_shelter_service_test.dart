import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/offline_shelter_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('OfflineShelterService', () {
    test('decodes the bundled Taiwan shelter data', () async {
      final json = await rootBundle.loadString(OfflineShelterService.assetPath);
      final shelters = decodeSheltersJson(json);

      expect(shelters.length, greaterThan(70000));
      expect(
        shelters.any(
          (shelter) =>
              shelter.name.contains('臨沂街75巷6號') &&
              shelter.latitude == 25.034645 &&
              shelter.longitude == 121.528668,
        ),
        isTrue,
      );
    });

    test(
      'returns all nearby shelters within ten kilometers ordered by distance',
      () {
        const shelters = [
          ShelterLocation(name: '最遠', latitude: 0, longitude: 0.2),
          ShelterLocation(name: '第三近', latitude: 0, longitude: 0.003),
          ShelterLocation(name: '最近', latitude: 0, longitude: 0.001),
          ShelterLocation(name: '第二近', latitude: 0, longitude: 0.002),
          ShelterLocation(name: '第四近', latitude: 0, longitude: 0.004),
          ShelterLocation(name: '第五近', latitude: 0, longitude: 0.005),
          ShelterLocation(name: '第六近', latitude: 0, longitude: 0.006),
        ];

        final nearby = OfflineShelterService.rankNearby(
          shelters,
          latitude: 0,
          longitude: 0,
        );

        expect(nearby.map((item) => item.location.name), [
          '最近',
          '第二近',
          '第三近',
          '第四近',
          '第五近',
          '第六近',
        ]);
        expect(nearby.first.distanceMeters, closeTo(111, 2));
        expect(
          nearby.every(
            (item) =>
                item.distanceMeters <= OfflineShelterService.searchRadiusMeters,
          ),
          isTrue,
        );
      },
    );

    test('returns an empty list when there are no shelters in range', () {
      final nearby = OfflineShelterService.rankNearby(
        const [ShelterLocation(name: '遠方', latitude: 0, longitude: 1)],
        latitude: 0,
        longitude: 0,
      );

      expect(nearby, isEmpty);
    });

    test('rejects malformed shelter records', () {
      expect(
        () => decodeSheltersJson('[["bad",121,"避難所"]]'),
        throwsFormatException,
      );
    });
  });
}
