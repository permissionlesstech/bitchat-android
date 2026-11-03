import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter_ui/screens/offline_navigation_screen.dart';
import 'package:flutter_ui/services/offline_shelter_service.dart';

void main() {
  testWidgets('offline shelter map exposes shelter navigation details', (
    tester,
  ) async {
    const shelter = ShelterLocation(
      name: '測試避難所',
      latitude: 23.9461,
      longitude: 120.9251,
    );
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

    await tester.pumpWidget(
      MaterialApp(
        home: OfflineNavigationScreen(
          position: position,
          shelters: const [
            NearbyShelter(location: shelter, distanceMeters: 20),
          ],
        ),
      ),
    );

    expect(find.text('防災避難所・離線地圖'), findsOneWidget);
    expect(find.text('附近避難所 1 / 1 處'), findsOneWidget);
    expect(find.text('要顯示的避難所數量'), findsOneWidget);
    expect(find.textContaining('測試避難所'), findsOneWidget);
    await tester.tap(find.byIcon(Icons.location_on_rounded));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('開始導航'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
