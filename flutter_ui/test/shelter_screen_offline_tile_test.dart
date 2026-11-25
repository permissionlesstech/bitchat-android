import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/mascot_service.dart';
import 'package:flutter_ui/screens/shelter_screen.dart';
import 'package:flutter_ui/widgets/mascot_assistant.dart';

void main() {
  testWidgets('shelter map supports dragging and keeps its helper open', (
    tester,
  ) async {
    final tileFinder = find.byWidgetPredicate((widget) {
      final key = widget.key;
      return widget is Positioned &&
          key is ValueKey<String> &&
          key.value.startsWith('tile-') &&
          !key.value.startsWith('tile-future-');
    });

    Map<Key, double> tileLefts() => {
      for (final tile in tester.widgetList<Positioned>(tileFinder))
        if (tile.key != null && tile.left != null) tile.key!: tile.left!,
    };

    await tester.pumpWidget(
      MaterialApp(
        navigatorObservers: [mascotRouteObserver],
        home: const ShelterScreen(),
        builder: (context, child) => Stack(
          children: [
            child!,
            const Positioned.fill(child: MascotAssistant()),
          ],
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('防災避難所・離線地圖'), findsOneWidget);
    expect(find.text('開始導航'), findsNothing);
    expect(find.widgetWithText(ChoiceChip, '醫院'), findsNothing);
    expect(find.widgetWithText(ChoiceChip, '消防局'), findsOneWidget);
    expect(mascotOptionsNotifier.value, shelterOptions);

    final searchField = find.byKey(const ValueKey('public-facility-search'));
    expect(searchField, findsOneWidget);
    expect(
      tester.getSize(find.byKey(const ValueKey('offline-map-viewport'))).height,
      greaterThan(0),
    );

    final tileLeftsBeforeDrag = tileLefts();
    final mapRect = tester.getRect(
      find.byKey(const ValueKey('offline-map-viewport')),
    );
    final dragStart = Offset(
      mapRect.center.dx,
      mapRect.top + mapRect.height * 0.75,
    );
    final drag = await tester.startGesture(dragStart);
    await drag.moveBy(const Offset(40, 18));
    await tester.pump();
    final dragLayer = find.descendant(
      of: find.byKey(const ValueKey('offline-map-drag-layer')),
      matching: find.byType(Transform),
    );
    final dragTransform = tester.widget<Transform>(dragLayer);
    expect(dragTransform.transform.getTranslation().x, closeTo(40, 0.1));
    expect(dragTransform.transform.getTranslation().y, closeTo(18, 0.1));
    expect(tileLefts(), tileLeftsBeforeDrag);

    await drag.up();
    await tester.pump();
    final tileLeftsAfterDrag = tileLefts();
    expect(
      tileLeftsAfterDrag.entries.any(
        (entry) =>
            tileLeftsBeforeDrag.containsKey(entry.key) &&
            (tileLeftsBeforeDrag[entry.key]! - entry.value).abs() > 0.1,
      ),
      isTrue,
    );

    await tester.tap(searchField);
    await tester.pump();
    final editableText = tester.widget<EditableText>(
      find.descendant(of: searchField, matching: find.byType(EditableText)),
    );
    expect(editableText.focusNode.hasFocus, isTrue);

    tester.testTextInput.hide();
    await tester.pump();
    final mascot = find.byKey(const ValueKey('mascot_character'));
    expect(mascot, findsOneWidget);
    await tester.tap(mascot);
    await tester.pump();
    expect(find.text('地圖怎麼使用？'), findsOneWidget);
    await tester.tap(mascot);
    await tester.pump();
    expect(find.text('地圖怎麼使用？'), findsOneWidget);
    expect(mascot, findsOneWidget);

    expect(tester.takeException(), isNull);
  });
}
