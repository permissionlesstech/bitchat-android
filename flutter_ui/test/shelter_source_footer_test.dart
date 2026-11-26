import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/screens/offline_tile_preview_screen.dart';

void main() {
  testWidgets('map exposes source, license, date, and hospital limitations', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(home: OfflineTilePreviewScreen()),
    );
    await tester.pump();

    final sourceButton = find.byKey(const ValueKey('offline-map-data-sources'));
    expect(sourceButton, findsOneWidget);
    await tester.tap(sourceButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('資料來源與授權'), findsNWidgets(2));
    expect(find.textContaining('原始資料授權尚未確認'), findsOneWidget);
    expect(find.textContaining('日期尚待確認'), findsOneWidget);
    expect(find.textContaining('地圖不顯示醫院'), findsOneWidget);
  });
}
