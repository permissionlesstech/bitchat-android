import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter_ui/screens/offline_map_screen.dart';
import 'package:flutter_ui/services/offline_public_facility_service.dart';
import 'package:flutter_ui/services/offline_shelter_service.dart';

import 'support/screen_sizes.dart';

void main() {
  testWidgets('shows type-specific facility markers and their details', (
    tester,
  ) async {
    useScreen(tester, smallPhoneLargeText);
    final position = Position(
      longitude: 120.925,
      latitude: 23.946,
      timestamp: DateTime(2026),
      accuracy: 10,
      altitude: 0,
      altitudeAccuracy: 0,
      heading: 0,
      headingAccuracy: 0,
      speed: 0,
      speedAccuracy: 0,
    );
    const facility = NearbyPublicFacility(
      location: PublicFacilityLocation(
        id: 'police-1',
        type: PublicFacilityType.policeStation,
        name: '測試警察局',
        latitude: 23.942,
        longitude: 120.925,
        address: '測試地址',
      ),
      distanceMeters: 450,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: OfflineMapScreen(
          position: position,
          shelters: const [],
          publicFacilities: const [facility],
          publicFacilityError: '醫學中心座標尚未提供；其他公共設施仍可顯示。',
        ),
      ),
    );
    await tester.pump();

    expect(find.textContaining('附近公共設施'), findsNothing);
    expect(find.byType(FilterChip), findsNothing);
    expect(find.text('醫學中心座標尚未提供；其他公共設施仍可顯示。'), findsOneWidget);
    final marker = find.byKey(
      const ValueKey('public-facility-marker-police-1'),
    );
    expect(marker, findsOneWidget);
    final mapStack = find
        .ancestor(of: marker, matching: find.byType(Stack))
        .first;
    final stackChildren = tester.widget<Stack>(mapStack).children;
    final markerLayerIndex = stackChildren.indexWhere(
      (child) => child.key == const ValueKey('public-facility-layer-police-1'),
    );
    final controlsLayerIndex = stackChildren.indexWhere(
      (child) => child.key == const ValueKey('offline-map-controls'),
    );
    expect(markerLayerIndex, greaterThanOrEqualTo(0));
    expect(controlsLayerIndex, greaterThan(markerLayerIndex));

    await tester.tap(marker);
    await tester.pump();

    expect(find.text('測試警察局'), findsOneWidget);
    expect(find.text('警察局'), findsAtLeastNWidgets(1));
    expect(find.text('測試地址'), findsOneWidget);
    expect(find.text('距離目前位置 450 公尺'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows bus stop markers and their details', (tester) async {
    useScreen(tester, smallPhoneLargeText);
    final position = Position(
      longitude: 120.925,
      latitude: 23.946,
      timestamp: DateTime(2026),
      accuracy: 10,
      altitude: 0,
      altitudeAccuracy: 0,
      heading: 0,
      headingAccuracy: 0,
      speed: 0,
      speedAccuracy: 0,
    );
    const facility = NearbyPublicFacility(
      location: PublicFacilityLocation(
        id: 'bus-stop-test',
        type: PublicFacilityType.busStop,
        name: '測試公車站',
        latitude: 23.942,
        longitude: 120.925,
        address: '測試站牌地址',
      ),
      distanceMeters: 450,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: OfflineMapScreen(
          position: position,
          shelters: const [],
          publicFacilities: const [facility],
        ),
      ),
    );
    await tester.pump();

    expect(find.textContaining('附近公共設施'), findsNothing);
    final marker = find.byKey(
      const ValueKey('public-facility-marker-bus-stop-test'),
    );
    expect(marker, findsOneWidget);
    expect(
      find.descendant(
        of: marker,
        matching: find.byIcon(Icons.directions_bus_rounded),
      ),
      findsOneWidget,
    );

    await tester.tap(marker);
    await tester.pump();

    expect(find.text('測試公車站'), findsOneWidget);
    expect(find.text('測試站牌地址'), findsOneWidget);
    expect(find.text('距離目前位置 450 公尺'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'searches emergency facilities and keeps the nearest 15 shelters',
    (tester) async {
      useScreen(tester, smallPhoneLargeText);
      final position = Position(
        longitude: 120.925,
        latitude: 23.946,
        timestamp: DateTime(2026),
        accuracy: 10,
        altitude: 0,
        altitudeAccuracy: 0,
        heading: 0,
        headingAccuracy: 0,
        speed: 0,
        speedAccuracy: 0,
      );
      final shelters = List.generate(
        20,
        (index) => NearbyShelter(
          location: ShelterLocation(
            name: '避難所 $index',
            latitude: 23.946 + index * 0.0001,
            longitude: 120.925,
          ),
          distanceMeters: index * 10,
        ),
      );
      const facilities = [
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'hospital-search',
            type: PublicFacilityType.hospital,
            name: '測試醫院',
            latitude: 23.942,
            longitude: 120.925,
          ),
          distanceMeters: 200,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'fire-search',
            type: PublicFacilityType.fireStation,
            name: '測試消防局',
            latitude: 23.943,
            longitude: 120.925,
          ),
          distanceMeters: 300,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'police-search',
            type: PublicFacilityType.policeStation,
            name: '測試警察局',
            latitude: 23.944,
            longitude: 120.925,
          ),
          distanceMeters: 400,
        ),
        NearbyPublicFacility(
          location: PublicFacilityLocation(
            id: 'station-search',
            type: PublicFacilityType.station,
            name: '測試車站',
            latitude: 23.941,
            longitude: 120.925,
          ),
          distanceMeters: 100,
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: OfflineMapScreen(
            position: position,
            shelters: shelters,
            publicFacilities: facilities,
          ),
        ),
      );
      await tester.pump();

      expect(find.text('附近避難所 15 / 20 處'), findsOneWidget);
      expect(find.text('要顯示的避難所數量'), findsNothing);
      final search = find.byKey(const ValueKey('public-facility-search'));
      await tester.enterText(search, '消防局');
      await tester.pump();

      expect(find.text('測試消防局 · 300 m'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('public-facility-marker-fire-search')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('public-facility-marker-police-search')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('public-facility-marker-station-search')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );
}
