import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/offline_shelter_service.dart';
import 'package:flutter_ui/widgets/nearby_shelter_panel.dart';

import 'support/screen_sizes.dart';

void main() {
  testWidgets('shows nearby shelters and selects a shelter for navigation', (
    tester,
  ) async {
    useScreen(tester, smallPhoneLargeText);
    NearbyShelter? selected;
    const shelter = NearbyShelter(
      location: ShelterLocation(
        name: '臺北市中正區臨沂街75巷6號',
        latitude: 25.034645,
        longitude: 121.528668,
      ),
      distanceMeters: 350,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: NearbyShelterPanel(
            isLoading: false,
            shelters: const [shelter],
            onSelect: (value) => selected = value,
          ),
        ),
      ),
    );

    expect(find.text('附近避難所'), findsOneWidget);
    expect(find.text('距離 350 公尺・步行導航'), findsOneWidget);
    await tester.tap(find.text('臺北市中正區臨沂街75巷6號'));
    expect(selected, shelter);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows location errors with a retry action', (tester) async {
    var retried = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: NearbyShelterPanel(
            isLoading: false,
            shelters: const [],
            error: '需要定位權限',
            onRetry: () => retried = true,
            onSelect: (_) {},
          ),
        ),
      ),
    );

    expect(find.text('需要定位權限'), findsOneWidget);
    await tester.tap(find.byTooltip('重新定位'));
    expect(retried, isTrue);
  });

  testWidgets('fits the available screen sizes', (tester) async {
    for (final screen in layoutCases) {
      useScreen(tester, screen);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: NearbyShelterPanel(
              isLoading: false,
              shelters: const [
                NearbyShelter(
                  location: ShelterLocation(
                    name: '臺北市中正區臨沂街75巷6號 (WOA01073)',
                    latitude: 25.034645,
                    longitude: 121.528668,
                  ),
                  distanceMeters: 1120,
                ),
              ],
              onSelect: (_) {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull, reason: '$screen');
    }
  });
}
