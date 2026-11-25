import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/offline_public_facility_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('OfflinePublicFacilityService', () {
    test('loads the offline public facility and bus stop snapshots', () async {
      final jsonAssets = await Future.wait([
        rootBundle.loadString(OfflinePublicFacilityService.assetPath),
        rootBundle.loadString(OfflinePublicFacilityService.busStopAssetPath),
      ]);
      final facilities = decodePublicFacilityJsonAssets(jsonAssets);

      expect(facilities, isNotEmpty);
      expect(
        facilities.map((facility) => facility.type).toSet(),
        containsAll([
          PublicFacilityType.station,
          PublicFacilityType.busStop,
          PublicFacilityType.fireStation,
          PublicFacilityType.policeStation,
        ]),
      );
      expect(
        facilities.where(
          (facility) => facility.type == PublicFacilityType.busStop,
        ),
        hasLength(131140),
      );
      expect(
        facilities.every(
          (facility) =>
              facility.latitude >= 21.5 &&
              facility.latitude <= 26.5 &&
              facility.longitude >= 117.5 &&
              facility.longitude <= 122.5,
        ),
        isTrue,
      );
    });

    test('decodes all supported facility categories', () {
      const json = '''
      [
        ["hospital-1","hospital",25,121,"醫院","地址"],
        ["station-1","station",25,121,"車站","地址"],
        ["bus-stop-1","busStop",25,121,"公車站","地址"],
        ["fire-1","fireStation",25,121,"消防局","地址"],
        ["police-1","policeStation",25,121,"警察局","地址"]
      ]
      ''';
      final facilities = decodePublicFacilitiesJson(json);

      expect(facilities, isNotEmpty);
      expect(
        facilities.map((facility) => facility.type).toSet(),
        PublicFacilityType.values.toSet(),
      );
    });

    test(
      'ranks nearby facilities and excludes those outside ten kilometers',
      () {
        const facilities = [
          PublicFacilityLocation(
            id: 'station-1',
            type: PublicFacilityType.station,
            name: '車站',
            latitude: 0,
            longitude: 0.02,
          ),
          PublicFacilityLocation(
            id: 'hospital-1',
            type: PublicFacilityType.hospital,
            name: '較遠醫院',
            latitude: 0,
            longitude: 0.11,
          ),
          PublicFacilityLocation(
            id: 'police-1',
            type: PublicFacilityType.policeStation,
            name: '警察局',
            latitude: 0,
            longitude: 0.01,
          ),
        ];

        final nearby = OfflinePublicFacilityService.rankNearby(
          facilities,
          latitude: 0,
          longitude: 0,
        );

        expect(nearby.map((item) => item.location.name), ['警察局', '車站']);
        expect(nearby.first.distanceMeters, closeTo(1112, 5));
      },
    );

    test('searches nearby hospitals, fire stations, and police stations', () {
      const facilities = [
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'hospital-1',
            type: PublicFacilityType.hospital,
            name: '中央醫院',
            latitude: 25,
            longitude: 121,
          ),
          distanceMeters: 100,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'fire-1',
            type: PublicFacilityType.fireStation,
            name: '中正消防分隊',
            latitude: 25,
            longitude: 121,
          ),
          distanceMeters: 200,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'police-1',
            type: PublicFacilityType.policeStation,
            name: '中正派出所',
            latitude: 25,
            longitude: 121,
          ),
          distanceMeters: 300,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'station-1',
            type: PublicFacilityType.station,
            name: '臺北車站',
            latitude: 25,
            longitude: 121,
          ),
          distanceMeters: 50,
        ),
      ];

      expect(
        OfflinePublicFacilityService.searchEmergencyFacilities(
          facilities,
          query: '消防局',
        ).map((facility) => facility.location.id),
        ['fire-1'],
      );
      expect(
        OfflinePublicFacilityService.searchEmergencyFacilities(
          facilities,
          query: '中正',
        ).map((facility) => facility.location.id),
        ['fire-1', 'police-1'],
      );
      expect(
        OfflinePublicFacilityService.searchEmergencyFacilities(
          facilities,
          query: '',
        ).map((facility) => facility.location.id),
        ['hospital-1', 'fire-1', 'police-1'],
      );
    });

    test('rejects malformed or unknown facility records', () {
      expect(
        () =>
            decodePublicFacilitiesJson('[["1","hospital","bad",121,"院",""] ]'),
        throwsFormatException,
      );
      expect(
        () => decodePublicFacilitiesJson('[["1","unknown",25,121,"院",""] ]'),
        throwsFormatException,
      );
    });
  });
}
