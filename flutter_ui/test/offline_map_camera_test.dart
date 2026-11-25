import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/offline_map_camera.dart';
import 'package:flutter/widgets.dart';

void main() {
  const viewport = Size(400, 800);
  const devicePixelRatio = 2.0;

  test('keeps the geographic point under a dragged finger anchored', () {
    const camera = OfflineMapCamera(
      latitude: 25.033,
      longitude: 121.5654,
      zoom: 17,
    );
    const start = Offset(140, 320);
    const end = Offset(176, 347);
    final (latitude, longitude) = camera.coordinatesAt(
      start,
      viewport,
      devicePixelRatio,
    );

    final moved = camera.updateForGesture(
      previousPosition: start,
      position: end,
      viewportSize: viewport,
      devicePixelRatio: devicePixelRatio,
    );

    expect(
      moved.project(latitude, longitude, viewport, devicePixelRatio).dx,
      closeTo(end.dx, 0.000001),
    );
    expect(
      moved.project(latitude, longitude, viewport, devicePixelRatio).dy,
      closeTo(end.dy, 0.000001),
    );
  });

  test('keeps the pinch focal point anchored when zooming', () {
    const camera = OfflineMapCamera(
      latitude: 25.033,
      longitude: 121.5654,
      zoom: 17,
    );
    const firstPointer = Offset(50, 300);
    const secondPointer = Offset(150, 300);
    const nextFirstPointer = Offset(0, 300);
    const previousFocalPoint = Offset(100, 300);
    const nextFocalPoint = Offset(75, 300);
    final (latitude, longitude) = camera.coordinatesAt(
      previousFocalPoint,
      viewport,
      devicePixelRatio,
    );

    final zoomed = camera.updateForGesture(
      previousPosition: firstPointer,
      position: nextFirstPointer,
      otherPointerPosition: secondPointer,
      viewportSize: viewport,
      devicePixelRatio: devicePixelRatio,
    );

    expect(zoomed.zoom.round(), 18);
    expect(
      zoomed.project(latitude, longitude, viewport, devicePixelRatio).dx,
      closeTo(nextFocalPoint.dx, 0.000001),
    );
    expect(
      zoomed.project(latitude, longitude, viewport, devicePixelRatio).dy,
      closeTo(nextFocalPoint.dy, 0.000001),
    );
  });
}
