import 'dart:math' as math;

import 'package:flutter/widgets.dart';

class OfflineMapCamera {
  const OfflineMapCamera({
    required this.latitude,
    required this.longitude,
    required this.zoom,
  });

  final double latitude;
  final double longitude;
  final double zoom;

  Offset project(
    double latitude,
    double longitude,
    Size viewportSize,
    double devicePixelRatio,
  ) {
    final scale = _worldScale(zoom.roundToDouble(), devicePixelRatio);
    final centerX = (this.longitude + 180.0) / 360.0;
    final pointX = (longitude + 180.0) / 360.0;
    var deltaX = pointX - centerX;
    if (deltaX > 0.5) deltaX -= 1;
    if (deltaX < -0.5) deltaX += 1;

    final centerY = _mercatorY(this.latitude);
    final pointY = _mercatorY(latitude);
    return Offset(
      viewportSize.width / 2 + deltaX * scale,
      viewportSize.height / 2 + (pointY - centerY) * scale,
    );
  }

  (double latitude, double longitude) coordinatesAt(
    Offset point,
    Size viewportSize,
    double devicePixelRatio,
  ) {
    final scale = _worldScale(zoom.roundToDouble(), devicePixelRatio);
    final x =
        (longitude + 180.0) / 360.0 +
        (point.dx - viewportSize.width / 2) / scale;
    final y =
        _mercatorY(latitude) + (point.dy - viewportSize.height / 2) / scale;
    final mercator = math.pi * (1 - 2 * y);
    return (
      (2 * math.atan(math.exp(mercator)) - math.pi / 2) * 180 / math.pi,
      (x * 360 - 180 + 540) % 360 - 180,
    );
  }

  OfflineMapCamera updateForGesture({
    required Offset previousPosition,
    required Offset position,
    Offset? otherPointerPosition,
    required Size viewportSize,
    required double devicePixelRatio,
  }) {
    var nextZoom = zoom;
    var previousFocalPoint = previousPosition;
    var nextFocalPoint = position;
    if (otherPointerPosition != null) {
      previousFocalPoint = (previousPosition + otherPointerPosition) / 2;
      nextFocalPoint = (position + otherPointerPosition) / 2;
      final previousDistance =
          (previousPosition - otherPointerPosition).distance;
      final nextDistance = (position - otherPointerPosition).distance;
      if (previousDistance > 0 && nextDistance > 0) {
        nextZoom = (zoom + math.log(nextDistance / previousDistance) / math.ln2)
            .clamp(1.0, 20.0)
            .toDouble();
      }
    }

    final currentScale = _worldScale(zoom.roundToDouble(), devicePixelRatio);
    final nextScale = _worldScale(nextZoom.roundToDouble(), devicePixelRatio);
    final centerX = (longitude + 180.0) / 360.0;
    final centerY = _mercatorY(latitude);
    final nextCenterX =
        centerX +
        (previousFocalPoint.dx - viewportSize.width / 2) / currentScale -
        (nextFocalPoint.dx - viewportSize.width / 2) / nextScale;
    final nextCenterY =
        centerY +
        (previousFocalPoint.dy - viewportSize.height / 2) / currentScale -
        (nextFocalPoint.dy - viewportSize.height / 2) / nextScale;
    final normalizedCenterX = (nextCenterX % 1.0 + 1.0) % 1.0;
    final clampedCenterY = nextCenterY.clamp(0.0, 1.0);
    final mercator = math.pi * (1 - 2 * clampedCenterY);

    return OfflineMapCamera(
      latitude:
          (2 * math.atan(math.exp(mercator)) - math.pi / 2) * 180 / math.pi,
      longitude: normalizedCenterX * 360 - 180,
      zoom: nextZoom,
    );
  }
}

double _worldScale(double zoom, double devicePixelRatio) =>
    (256.0 * math.pow(2, zoom) / devicePixelRatio).toDouble();

double _mercatorY(double latitude) {
  final latitudeRadians =
      latitude.clamp(-85.05112878, 85.05112878) * math.pi / 180;
  return (1 -
          math.log(math.tan(latitudeRadians) + 1 / math.cos(latitudeRadians)) /
              math.pi) /
      2;
}
