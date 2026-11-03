import 'dart:async';
import 'dart:math' as math;

import 'package:agus_maps_flutter/agus_maps_flutter.dart' as maps;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter/services.dart';

import '../services/offline_shelter_service.dart';

class _OfflineMapRuntime {
  static Future<List<String>>? _mapPathsFuture;
  static Future<int>? _surfaceFuture;
  static int? _textureId;
  static Size? _surfaceSize;
  static double? _surfaceDensity;
  static bool _mapsRegistered = false;

  static int? get textureId => _textureId;

  static Future<List<String>> loadMapPaths() async {
    final pending = _mapPathsFuture;
    if (pending != null) return pending;

    final loading = _loadMapPaths();
    _mapPathsFuture = loading;
    try {
      return await loading;
    } catch (_) {
      _mapPathsFuture = null;
      rethrow;
    }
  }

  static Future<List<String>> _loadMapPaths() async {
    await maps.extractMap('assets/maps/icudt75l.dat');
    final dataPath = await maps.extractDataFiles();
    maps.initWithPaths(dataPath, dataPath);

    final assets = const [
      'assets/maps/World.mwm',
      'assets/maps/WorldCoasts.mwm',
      'assets/maps/Taiwan.mwm',
    ];
    for (final asset in assets) {
      final bytes = await rootBundle.load(asset);
      debugPrint(
        '[OfflineMap] Asset verified $asset (${bytes.lengthInBytes} bytes)',
      );
      if (bytes.lengthInBytes < 1024) {
        throw Exception('$asset is invalid or truncated');
      }
    }

    return [
      await maps.extractMap(assets[0]),
      await maps.extractMap(assets[1]),
      await maps.extractMap(assets[2]),
    ];
  }

  static Future<int> ensureSurface({
    required Size logicalSize,
    required double devicePixelRatio,
    required List<String> mapPaths,
    required double latitude,
    required double longitude,
    required int zoom,
  }) async {
    final pending = _surfaceFuture;
    if (_textureId == null) {
      if (pending == null) {
        final creation = _createSurface(
          logicalSize: logicalSize,
          devicePixelRatio: devicePixelRatio,
          mapPaths: mapPaths,
          latitude: latitude,
          longitude: longitude,
          zoom: zoom,
        );
        _surfaceFuture = creation;
      }
      try {
        return await _surfaceFuture!;
      } catch (_) {
        if (_textureId == null) _surfaceFuture = null;
        rethrow;
      }
    }

    if (pending != null) await pending;
    await _resizeSurface(logicalSize, devicePixelRatio);
    if (!_mapsRegistered) {
      _registerMaps(mapPaths, latitude, longitude, zoom);
    }
    return _textureId!;
  }

  static Future<int> _createSurface({
    required Size logicalSize,
    required double devicePixelRatio,
    required List<String> mapPaths,
    required double latitude,
    required double longitude,
    required int zoom,
  }) async {
    final pixelWidth = (logicalSize.width * devicePixelRatio).round();
    final pixelHeight = (logicalSize.height * devicePixelRatio).round();
    if (pixelWidth <= 0 || pixelHeight <= 0) {
      throw StateError('離線地圖 Surface 尺寸無效');
    }

    debugPrint(
      '[OfflineMap] Creating shared native Surface: ${pixelWidth}x$pixelHeight px, '
      'DPR=${devicePixelRatio.toStringAsFixed(2)}',
    );
    final textureId = await maps.createMapSurface(
      width: pixelWidth,
      height: pixelHeight,
      density: devicePixelRatio,
    );
    _textureId = textureId;
    _surfaceSize = logicalSize;
    _surfaceDensity = devicePixelRatio;
    _registerMaps(mapPaths, latitude, longitude, zoom);
    return textureId;
  }

  static Future<void> _resizeSurface(
    Size logicalSize,
    double devicePixelRatio,
  ) async {
    if (_surfaceSize == logicalSize && _surfaceDensity == devicePixelRatio) {
      return;
    }

    final pixelWidth = (logicalSize.width * devicePixelRatio).round();
    final pixelHeight = (logicalSize.height * devicePixelRatio).round();
    await maps.resizeMapSurface(
      pixelWidth,
      pixelHeight,
      density: devicePixelRatio,
    );
    _surfaceSize = logicalSize;
    _surfaceDensity = devicePixelRatio;
    debugPrint(
      '[OfflineMap] Shared native Surface resized to ${pixelWidth}x$pixelHeight px',
    );
  }

  static void _registerMaps(
    List<String> mapPaths,
    double latitude,
    double longitude,
    int zoom,
  ) {
    for (final path in mapPaths) {
      final fileName = path.split('/').last.split(r'\').last;
      final result = maps.registerSingleMapWithVersion(path, 260106);
      debugPrint(
        '[OfflineMap] Registered $fileName with engine snapshot 260106: $result',
      );
      if (result != 0 && result != 1) {
        throw StateError('註冊 $fileName 失敗，原生引擎回傳 $result');
      }
    }

    _mapsRegistered = true;
    maps.setView(latitude, longitude, zoom);
    maps.invalidateMap();
    maps.forceRedraw();
    maps.debugListMwms();
    maps.debugCheckPoint(latitude, longitude);
  }
}

class OfflineNavigationScreen extends StatefulWidget {
  const OfflineNavigationScreen({
    super.key,
    required this.position,
    required this.shelters,
    this.destination,
    this.onRefresh,
    this.isRefreshing = false,
  });

  final Position position;
  final List<NearbyShelter> shelters;
  final NearbyShelter? destination;
  final Future<void> Function()? onRefresh;
  final bool isRefreshing;

  @override
  State<OfflineNavigationScreen> createState() =>
      _OfflineNavigationScreenState();
}

class _OfflineNavigationScreenState extends State<OfflineNavigationScreen> {
  final maps.AgusMapController _mapController = maps.AgusMapController();
  late final TextEditingController _shelterCountController;
  bool _dataReady = false;
  String? _error;
  List<String> _mapPaths = const [];
  int? _mapTextureId;
  bool _surfaceCreationStarted = false;
  double? _surfaceDensity;
  Size _mapViewportSize = Size.zero;
  final Map<int, Offset> _activePointers = {};
  late Position _position = widget.position;
  late double _centerLat = widget.position.latitude;
  late double _centerLon = widget.position.longitude;
  late double _zoom = 17;
  late int _visibleShelterCount = math.min(5, widget.shelters.length);
  NearbyShelter? _selectedShelter;
  StreamSubscription<Position>? _positionSubscription;
  Timer? _navigationStatusTimer;
  NearbyShelter? _navigationDestination;
  maps.NavigationStatus? _navigationStatus;
  bool _isBuildingNavigationRoute = false;
  int _navigationGeneration = 0;

  @override
  void didUpdateWidget(OfflineNavigationScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.destination != widget.destination &&
        widget.destination != null) {
      _selectShelter(widget.destination!, zoom: 16);
    }
    if (oldWidget.position != widget.position) {
      _position = widget.position;
      if (_navigationDestination != null) {
        try {
          _updateNativeLocation(_position);
        } catch (error) {
          _failNavigation(error);
        }
      }
    }
    if (oldWidget.shelters != widget.shelters) {
      _visibleShelterCount = math.min(
        _visibleShelterCount,
        widget.shelters.length,
      );
      _shelterCountController.text = '$_visibleShelterCount';
    }
  }

  @override
  void initState() {
    super.initState();
    _selectedShelter = widget.destination;
    _shelterCountController = TextEditingController(
      text: '$_visibleShelterCount',
    );
    _positionSubscription =
        Geolocator.getPositionStream(
          locationSettings: const LocationSettings(
            accuracy: LocationAccuracy.high,
            distanceFilter: 5,
          ),
        ).listen(
          _onPositionChanged,
          onError: (Object error, StackTrace stackTrace) {
            debugPrint('[OfflineMap] Location updates failed: $error');
            if (_navigationDestination != null) {
              _failNavigation(StateError('定位更新中斷，無法繼續導航：$error'));
            }
          },
        );
    SchedulerBinding.instance.addPostFrameCallback((_) {
      unawaited(_initializeMap());
    });
  }

  @override
  void dispose() {
    _shelterCountController.dispose();
    _navigationStatusTimer?.cancel();
    if (_navigationDestination != null) {
      maps.closeNavigationRoute(removeRoutePoints: true);
    }
    unawaited(_positionSubscription?.cancel());
    super.dispose();
  }

  List<NearbyShelter> get _visibleShelters =>
      widget.shelters.take(_visibleShelterCount).toList(growable: false);

  void _applyShelterCount() {
    final requestedCount = int.tryParse(_shelterCountController.text);
    if (requestedCount == null) {
      _shelterCountController.text = '$_visibleShelterCount';
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('請輸入要顯示的避難所數量。')));
      return;
    }

    final count = widget.shelters.isEmpty
        ? 0
        : requestedCount.clamp(0, widget.shelters.length);
    setState(() => _visibleShelterCount = count);
    _shelterCountController.text = '$count';
  }

  Future<void> _initializeMap() async {
    try {
      final mapPaths = await _OfflineMapRuntime.loadMapPaths();

      if (!mounted) return;
      setState(() {
        _dataReady = true;
        _error = null;
        _mapPaths = mapPaths;
        _mapTextureId = _OfflineMapRuntime.textureId;
      });
      if (_mapTextureId != null) {
        maps.setView(_centerLat, _centerLon, _zoom.round());
      }
    } catch (error) {
      debugPrint('Unable to initialize Agus Maps: $error');
      if (!mounted) return;
      setState(() {
        _error = '離線地圖引擎初始化失敗：$error';
      });
    }
  }

  Future<void> _ensureMapSurface(
    Size logicalSize,
    double devicePixelRatio,
  ) async {
    if (!mounted || !_dataReady || logicalSize.isEmpty) return;
    final pixelWidth = (logicalSize.width * devicePixelRatio).round();
    final pixelHeight = (logicalSize.height * devicePixelRatio).round();
    if (pixelWidth <= 0 || pixelHeight <= 0) return;

    if (_surfaceCreationStarted) return;
    _surfaceCreationStarted = true;
    try {
      final textureId = await _OfflineMapRuntime.ensureSurface(
        logicalSize: logicalSize,
        devicePixelRatio: devicePixelRatio,
        mapPaths: _mapPaths,
        latitude: _centerLat,
        longitude: _centerLon,
        zoom: _zoom.round(),
      );
      if (!mounted) return;
      setState(() {
        _mapTextureId = textureId;
        _error = null;
      });
      _surfaceDensity = devicePixelRatio;
    } catch (error) {
      _surfaceCreationStarted = false;
      debugPrint('[OfflineMap] Shared Surface initialization failed: $error');
      if (!mounted) return;
      setState(() => _error = '離線地圖 Surface 初始化失敗：$error');
    }
  }

  void _sendPointerEvent(PointerEvent event, maps.TouchType type) {
    final ratio = _surfaceDensity ?? MediaQuery.devicePixelRatioOf(context);
    MapEntry<int, Offset>? otherPointer;
    for (final entry in _activePointers.entries) {
      if (entry.key != event.pointer) {
        otherPointer = entry;
        break;
      }
    }
    maps.sendTouchEvent(
      type,
      event.pointer,
      event.localPosition.dx * ratio,
      event.localPosition.dy * ratio,
      id2: otherPointer?.key ?? -1,
      x2: (otherPointer?.value.dx ?? 0) * ratio,
      y2: (otherPointer?.value.dy ?? 0) * ratio,
    );
  }

  void _onPointerDown(PointerDownEvent event) {
    _activePointers[event.pointer] = event.localPosition;
    _sendPointerEvent(event, maps.TouchType.down);
  }

  void _onPointerMove(PointerMoveEvent event) {
    final previous = _activePointers[event.pointer];
    if (previous != null) {
      final otherPointer = _activePointers.entries
          .where((entry) => entry.key != event.pointer)
          .firstOrNull
          ?.value;
      _updateMapCenterForGesture(
        previous,
        event.localPosition,
        otherPointerPosition: otherPointer,
      );
    }
    _activePointers[event.pointer] = event.localPosition;
    _sendPointerEvent(event, maps.TouchType.move);
  }

  void _updateMapCenterForGesture(
    Offset previousPosition,
    Offset position, {
    Offset? otherPointerPosition,
  }) {
    var nextZoom = _zoom;
    var previousFocalPoint = previousPosition;
    var nextFocalPoint = position;
    if (otherPointerPosition != null) {
      previousFocalPoint = (previousPosition + otherPointerPosition) / 2;
      nextFocalPoint = (position + otherPointerPosition) / 2;
      final previousDistance =
          (previousPosition - otherPointerPosition).distance;
      final nextDistance = (position - otherPointerPosition).distance;
      if (previousDistance > 0 && nextDistance > 0) {
        nextZoom += math.log(nextDistance / previousDistance) / math.ln2;
        nextZoom = nextZoom.clamp(1.0, 20.0);
      }
    }
    if (previousFocalPoint == nextFocalPoint && nextZoom == _zoom) return;

    final oldScale = _worldScale(_zoom);
    final nextScale = _worldScale(nextZoom);
    final viewportCenter = Offset(
      _mapViewportSize.width / 2,
      _mapViewportSize.height / 2,
    );
    final centerX = (_centerLon + 180.0) / 360.0;
    final centerY = _mercatorY(_centerLat);
    final nextCenterX =
        centerX +
        (previousFocalPoint.dx - viewportCenter.dx) / oldScale -
        (nextFocalPoint.dx - viewportCenter.dx) / nextScale;
    final nextCenterY =
        centerY +
        (previousFocalPoint.dy - viewportCenter.dy) / oldScale -
        (nextFocalPoint.dy - viewportCenter.dy) / nextScale;
    final wrappedCenterX = nextCenterX % 1.0;
    final clampedCenterY = nextCenterY.clamp(0.0, 1.0);
    final longitude = wrappedCenterX * 360.0 - 180.0;
    final mercator = math.pi * (1.0 - 2.0 * clampedCenterY);
    final latitude =
        (2.0 * math.atan(math.exp(mercator)) - math.pi / 2.0) * 180.0 / math.pi;
    setState(() {
      _centerLat = latitude;
      _centerLon = longitude;
      _zoom = nextZoom;
    });
  }

  void _onPointerUp(PointerUpEvent event) {
    _sendPointerEvent(event, maps.TouchType.up);
    _activePointers.remove(event.pointer);
  }

  void _onPointerCancel(PointerCancelEvent event) {
    _sendPointerEvent(event, maps.TouchType.cancel);
    _activePointers.remove(event.pointer);
  }

  void _recenter() {
    setState(() {
      _centerLat = _position.latitude;
      _centerLon = _position.longitude;
      _zoom = 17;
    });
    _mapController.moveToLocation(
      _position.latitude,
      _position.longitude,
      _zoom.round(),
    );
  }

  void _selectShelter(NearbyShelter shelter, {int zoom = 16}) {
    setState(() {
      _selectedShelter = shelter;
      _centerLat = shelter.location.latitude;
      _centerLon = shelter.location.longitude;
      _zoom = zoom.toDouble();
    });
    _mapController.moveToLocation(_centerLat, _centerLon, zoom);
  }

  void _onPositionChanged(Position position) {
    if (!mounted) return;
    setState(() => _position = position);
    if (_navigationDestination == null) return;

    try {
      _updateNativeLocation(position);
    } catch (error) {
      _failNavigation(error);
    }
  }

  void _updateNativeLocation(Position position) {
    final result = maps.updateNavigationLocation(
      latitude: position.latitude,
      longitude: position.longitude,
      horizontalAccuracy: position.accuracy,
      timestampSeconds: position.timestamp.millisecondsSinceEpoch / 1000,
      bearingDegrees: position.heading.isFinite && position.heading >= 0
          ? position.heading
          : -1,
      speedMetersPerSecond: position.speed.isFinite && position.speed >= 0
          ? position.speed
          : -1,
    );
    if (result != 1) {
      throw StateError('原生導航定位更新失敗（$result）');
    }
  }

  Future<void> _startNavigation(NearbyShelter shelter) async {
    if (_mapTextureId == null) {
      _showNavigationError(StateError('離線地圖尚未準備完成，請稍後再試。'));
      return;
    }

    final generation = ++_navigationGeneration;
    _navigationStatusTimer?.cancel();
    setState(() {
      _selectedShelter = shelter;
      _navigationDestination = shelter;
      _navigationStatus = null;
      _isBuildingNavigationRoute = true;
    });

    try {
      _updateNativeLocation(_position);
      maps.closeNavigationRoute(removeRoutePoints: true);

      final routerResult = maps.setNavigationRouter(
        maps.NavigationRouterType.vehicle,
      );
      if (routerResult != 1) {
        throw StateError('無法啟用離線道路規劃引擎（$routerResult）');
      }

      final startResult = maps.addNavigationRoutePoint(
        type: maps.NavigationRoutePointType.start,
        lat: _position.latitude,
        lon: _position.longitude,
        title: '目前位置',
        isMyPosition: true,
      );
      if (startResult != 1) {
        throw StateError('無法設定導航起點（$startResult）');
      }

      final finishResult = maps.addNavigationRoutePoint(
        type: maps.NavigationRoutePointType.finish,
        lat: shelter.location.latitude,
        lon: shelter.location.longitude,
        title: shelter.location.name,
        subtitle: '防災避難所',
      );
      if (finishResult != 1) {
        throw StateError('無法設定避難所目的地（$finishResult）');
      }

      final buildResult = maps.buildNavigationRoute();
      if (buildResult != 1) {
        throw StateError('離線道路路線計算未能啟動（$buildResult）');
      }

      final status = await _waitForNavigationRoute(generation);
      if (!mounted || generation != _navigationGeneration) return;
      final followResult = maps.followNavigationRoute();
      if (followResult != 1) {
        throw StateError('路線已計算，但無法開始導航（$followResult）');
      }

      setState(() {
        _navigationStatus = status;
        _isBuildingNavigationRoute = false;
      });
      _navigationStatusTimer = Timer.periodic(
        const Duration(seconds: 1),
        (_) => _refreshNavigationStatus(generation),
      );
    } catch (error) {
      if (!mounted || generation != _navigationGeneration) return;
      _failNavigation(error);
    }
  }

  Future<maps.NavigationStatus> _waitForNavigationRoute(int generation) async {
    final startedAt = DateTime.now();
    while (DateTime.now().difference(startedAt) < const Duration(seconds: 30)) {
      if (!mounted || generation != _navigationGeneration) {
        throw StateError('導航作業已取消');
      }
      final status = maps.getNavigationStatus();
      if (status == null) {
        throw StateError('原生導航引擎未回傳路線狀態');
      }
      if (status.isBuilt && status.isValid) return status;
      if (!status.isBuilding &&
          DateTime.now().difference(startedAt) > const Duration(seconds: 2)) {
        throw StateError('找不到可行的離線道路路線，請確認目的地附近有地圖道路資料。');
      }
      await Future<void>.delayed(const Duration(milliseconds: 300));
    }
    throw TimeoutException('離線道路路線計算逾時，請稍後重試。');
  }

  void _refreshNavigationStatus(int generation) {
    if (!mounted ||
        generation != _navigationGeneration ||
        _navigationDestination == null) {
      return;
    }
    final status = maps.getNavigationStatus();
    if (status == null) {
      _failNavigation(StateError('無法讀取原生導航狀態'));
      return;
    }
    if (!status.isActive || !status.isValid) {
      _failNavigation(StateError('原生導航路線已失效'));
      return;
    }
    setState(() => _navigationStatus = status);
    if (status.sessionState == maps.NavigationSessionState.routeFinished) {
      _navigationStatusTimer?.cancel();
    }
  }

  void _stopNavigation() {
    ++_navigationGeneration;
    _navigationStatusTimer?.cancel();
    maps.closeNavigationRoute(removeRoutePoints: true);
    if (!mounted) return;
    setState(() {
      _navigationDestination = null;
      _navigationStatus = null;
      _isBuildingNavigationRoute = false;
    });
  }

  void _failNavigation(Object error) {
    ++_navigationGeneration;
    _navigationStatusTimer?.cancel();
    maps.closeNavigationRoute(removeRoutePoints: true);
    if (!mounted) return;
    setState(() {
      _navigationDestination = null;
      _navigationStatus = null;
      _isBuildingNavigationRoute = false;
    });
    _showNavigationError(error);
  }

  void _showNavigationError(Object error) {
    debugPrint('[OfflineNavigation] Failed: $error');
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('離線導航失敗：$error')));
  }

  String _turnInstruction(maps.NavigationStatus status) {
    return switch (status.turn) {
      0 => '繼續行駛',
      1 => '直行',
      2 => '右轉',
      3 => '大幅右轉',
      4 => '稍微靠右',
      5 => '左轉',
      6 => '大幅左轉',
      7 => '稍微靠左',
      8 || 9 => '迴轉',
      10 => status.exitNumber > 0 ? '進入圓環，走第 ${status.exitNumber} 個出口' : '進入圓環',
      11 => '離開圓環',
      12 => '沿圓環行駛',
      13 => '沿道路繼續',
      14 => '抵達目的地',
      15 => '靠左駛離快速道路',
      16 => '靠右駛離快速道路',
      _ => '沿道路繼續',
    };
  }

  String _formatNavigationDistance(maps.NavigationDistance distance) {
    final value = distance.value;
    if (!value.isFinite || value < 0) return '—';
    return switch (distance.unit) {
      maps.NavigationDistanceUnit.meters => '${value.round()} 公尺',
      maps.NavigationDistanceUnit.kilometers =>
        '${value.toStringAsFixed(1)} 公里',
      maps.NavigationDistanceUnit.feet => '${value.round()} 英呎',
      maps.NavigationDistanceUnit.miles => '${value.toStringAsFixed(1)} 英里',
    };
  }

  String _formatNavigationTime(int seconds) {
    if (seconds <= 0) return '—';
    final duration = Duration(seconds: seconds);
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);
    return hours > 0 ? '$hours 小時 $minutes 分' : '$minutes 分鐘';
  }

  Widget _buildNavigationPanel() {
    final destination = _navigationDestination!;
    final status = _navigationStatus;
    final isBuildingRoute =
        _isBuildingNavigationRoute || (status?.isBuilding ?? false);
    final isArrived =
        status?.sessionState == maps.NavigationSessionState.routeFinished;
    final isRebuildingRoute =
        status?.sessionState == maps.NavigationSessionState.routeNeedsRebuild ||
        status?.sessionState == maps.NavigationSessionState.routeRebuilding ||
        status?.sessionState == maps.NavigationSessionState.routeBuilding;
    final instruction = isBuildingRoute
        ? '正在計算離線道路路線…'
        : isArrived
        ? '已抵達避難所'
        : isRebuildingRoute
        ? '正在重新規劃路線…'
        : status?.hasFollowingInfo == true
        ? _turnInstruction(status!)
        : '路線已規劃，等待定位資訊';
    final progress = status?.completionPercent;

    return Positioned(
      left: 12,
      right: 12,
      bottom: 12,
      child: SafeArea(
        top: false,
        child: Card(
          elevation: 8,
          color: Theme.of(context).colorScheme.surface,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    if (isBuildingRoute)
                      const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2.5),
                      )
                    else
                      Icon(
                        isArrived
                            ? Icons.flag_rounded
                            : Icons.directions_rounded,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            destination.location.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.titleSmall
                                ?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          Text(
                            instruction,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                          if (status?.hasFollowingInfo == true &&
                              status!.currentStreet.isNotEmpty)
                            Text(
                              '目前：${status.currentStreet}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          if (status?.hasFollowingInfo == true &&
                              status!.nextStreet.isNotEmpty)
                            Text(
                              '往 ${status.nextStreet}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                        ],
                      ),
                    ),
                    IconButton(
                      onPressed: _stopNavigation,
                      tooltip: '結束導航',
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
                if (!isBuildingRoute && status != null) ...[
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '轉向 ${status.hasFollowingInfo ? _formatNavigationDistance(status.distanceToTurn) : '—'}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                      Expanded(
                        child: Text(
                          '剩餘 ${_formatNavigationDistance(status.distanceToTarget)}',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                      Expanded(
                        child: Text(
                          '約 ${_formatNavigationTime(status.totalTimeSeconds)}',
                          textAlign: TextAlign.end,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ],
                  ),
                  if (progress != null && progress.isFinite)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: LinearProgressIndicator(
                        value: (progress / 100).clamp(0.0, 1.0),
                      ),
                    ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _showShelterDetails(NearbyShelter shelter) async {
    final distance = shelter.distanceMeters < 1000
        ? '${shelter.distanceMeters.round()} 公尺'
        : '${(shelter.distanceMeters / 1000).toStringAsFixed(1)} 公里';
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                shelter.location.name,
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  const Icon(Icons.social_distance_rounded, size: 20),
                  const SizedBox(width: 8),
                  Text('距離目前位置 $distance'),
                ],
              ),
              const SizedBox(height: 6),
              Text('緯度 ${shelter.location.latitude.toStringAsFixed(6)}'),
              Text('經度 ${shelter.location.longitude.toStringAsFixed(6)}'),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () {
                    Navigator.of(context).pop();
                    unawaited(_startNavigation(shelter));
                  },
                  icon: const Icon(Icons.directions_rounded),
                  label: const Text('開始導航'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Offset _projectToMap(double lat, double lon, Size size) {
    double mercatorX(double longitude) => (longitude + 180.0) / 360.0;
    final latitudeRadians = lat * math.pi / 180.0;
    final centerX = mercatorX(_centerLon);
    final centerY = _mercatorY(_centerLat);
    double mercatorY(double latitudeRadians) =>
        (1.0 -
            math.log(
                  math.tan(latitudeRadians) + 1.0 / math.cos(latitudeRadians),
                ) /
                math.pi) /
        2.0;
    final scale = _worldScale(_zoom);
    final pointX = mercatorX(lon);
    final pointY = mercatorY(latitudeRadians);
    return Offset(
      size.width / 2 + (pointX - centerX) * scale,
      size.height / 2 + (pointY - centerY) * scale,
    );
  }

  double _mercatorY(double latitude) {
    final latitudeRadians =
        latitude.clamp(-85.05112878, 85.05112878) * math.pi / 180.0;
    return (1.0 -
            math.log(
                  math.tan(latitudeRadians) + 1.0 / math.cos(latitudeRadians),
                ) /
                math.pi) /
        2.0;
  }

  double _worldScale(double zoom) {
    final devicePixelRatio =
        _surfaceDensity ?? MediaQuery.devicePixelRatioOf(context);
    return 256.0 * math.pow(2, zoom) / devicePixelRatio;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('防災避難所・離線地圖'),
        actions: [
          IconButton(
            onPressed: widget.isRefreshing ? null : widget.onRefresh,
            tooltip: '重新定位',
            icon: widget.isRefreshing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.my_location_rounded),
          ),
        ],
      ),
      body: LayoutBuilder(
        builder: (context, constraints) {
          final mapSize = constraints.biggest;
          _mapViewportSize = mapSize;
          debugPrint(
            '[OfflineMap] Layout constraints=${constraints.maxWidth.toStringAsFixed(0)}x${constraints.maxHeight.toStringAsFixed(0)}, dataReady=$_dataReady',
          );
          if (!constraints.hasBoundedWidth ||
              !constraints.hasBoundedHeight ||
              mapSize.width <= 0 ||
              mapSize.height <= 0) {
            return const Center(child: Text('離線地圖版面約束無效'));
          }
          if (_dataReady) {
            final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
            SchedulerBinding.instance.addPostFrameCallback((_) {
              unawaited(_ensureMapSurface(mapSize, devicePixelRatio));
            });
          }
          final visibleShelters = _visibleShelters;
          final userPoint = _projectToMap(
            _position.latitude,
            _position.longitude,
            mapSize,
          );
          return SizedBox.expand(
            child: Stack(
              children: [
                Positioned.fill(
                  child: _mapTextureId == null
                      ? const Center(child: CircularProgressIndicator())
                      : Listener(
                          onPointerDown: _onPointerDown,
                          onPointerMove: _onPointerMove,
                          onPointerUp: _onPointerUp,
                          onPointerCancel: _onPointerCancel,
                          child: Texture(
                            textureId: _mapTextureId!,
                            filterQuality: FilterQuality.low,
                          ),
                        ),
                ),
                Positioned(
                  left: 12,
                  right: 12,
                  top: 10,
                  child: Material(
                    color: Colors.white.withValues(alpha: 0.96),
                    elevation: 4,
                    borderRadius: BorderRadius.circular(12),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(10, 7, 10, 7),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(
                                Icons.health_and_safety_rounded,
                                size: 18,
                                color: Color(0xFFB45F42),
                              ),
                              const SizedBox(width: 6),
                              Expanded(
                                child: Text(
                                  '附近避難所 ${visibleShelters.length} / ${widget.shelters.length} 處',
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 6),
                          SizedBox(
                            height: 52,
                            child: TextField(
                              controller: _shelterCountController,
                              keyboardType: TextInputType.number,
                              textInputAction: TextInputAction.search,
                              inputFormatters: [
                                FilteringTextInputFormatter.digitsOnly,
                              ],
                              onSubmitted: (_) => _applyShelterCount(),
                              decoration: InputDecoration(
                                labelText: '要顯示的避難所數量',
                                prefixIcon: const Icon(Icons.search_rounded),
                                suffixIcon: IconButton(
                                  onPressed: _applyShelterCount,
                                  tooltip: '套用顯示數量',
                                  icon: const Icon(Icons.arrow_forward_rounded),
                                ),
                                isDense: true,
                                filled: true,
                                fillColor: Colors.white,
                                contentPadding: const EdgeInsets.symmetric(
                                  vertical: 8,
                                ),
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(10),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(height: 6),
                          SizedBox(
                            height: 38,
                            child: ListView.separated(
                              scrollDirection: Axis.horizontal,
                              itemCount: visibleShelters.length,
                              separatorBuilder: (_, _) =>
                                  const SizedBox(width: 6),
                              itemBuilder: (_, index) {
                                final shelter = visibleShelters[index];
                                final selected = identical(
                                  _selectedShelter,
                                  shelter,
                                );
                                final distance = shelter.distanceMeters < 1000
                                    ? '${shelter.distanceMeters.round()} m'
                                    : '${(shelter.distanceMeters / 1000).toStringAsFixed(1)} km';
                                return ActionChip(
                                  avatar: Icon(
                                    selected
                                        ? Icons.place_rounded
                                        : Icons.location_on_outlined,
                                    size: 16,
                                    color: const Color(0xFFB45F42),
                                  ),
                                  label: Text(
                                    '${shelter.location.name} · $distance',
                                  ),
                                  backgroundColor: selected
                                      ? const Color(0xFFFFE0D4)
                                      : Colors.white,
                                  onPressed: () =>
                                      _selectShelter(shelter, zoom: 17),
                                );
                              },
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                Positioned(
                  right: 12,
                  top: 166,
                  child: FloatingActionButton.small(
                    heroTag: 'agus_recenter',
                    onPressed: _recenter,
                    tooltip: '回到目前位置',
                    child: const Icon(
                      Icons.navigation_rounded,
                      color: Color(0xFF1976D2),
                    ),
                  ),
                ),
                if (userPoint.dx >= -30 &&
                    userPoint.dx <= mapSize.width + 30 &&
                    userPoint.dy >= -30 &&
                    userPoint.dy <= mapSize.height + 30)
                  Positioned(
                    left: userPoint.dx - 18,
                    top: userPoint.dy - 18,
                    width: 36,
                    height: 36,
                    child: Transform.rotate(
                      angle:
                          ((_position.heading.isFinite && _position.heading >= 0
                              ? _position.heading
                              : 0) *
                          math.pi /
                          180),
                      child: const Icon(
                        Icons.navigation_rounded,
                        size: 36,
                        color: Color(0xFF1976D2),
                        shadows: [Shadow(color: Colors.white, blurRadius: 5)],
                      ),
                    ),
                  ),
                for (final shelter in visibleShelters)
                  Builder(
                    builder: (context) {
                      final point = _projectToMap(
                        shelter.location.latitude,
                        shelter.location.longitude,
                        mapSize,
                      );
                      if (point.dx < -30 ||
                          point.dx > mapSize.width + 30 ||
                          point.dy < 100 ||
                          point.dy > mapSize.height + 30) {
                        return const SizedBox.shrink();
                      }
                      final selected = identical(_selectedShelter, shelter);
                      return Positioned(
                        left: point.dx - 20,
                        top: point.dy - 40,
                        width: 40,
                        height: 44,
                        child: GestureDetector(
                          onTap: () => _showShelterDetails(shelter),
                          child: Icon(
                            Icons.location_on_rounded,
                            size: selected ? 44 : 38,
                            color: selected
                                ? const Color(0xFFD84315)
                                : const Color(0xFFB45F42),
                            shadows: const [
                              Shadow(color: Colors.white, blurRadius: 4),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                if (_navigationDestination != null) _buildNavigationPanel(),
                if (_error != null)
                  Positioned(
                    left: 12,
                    right: 12,
                    top: 210,
                    child: Material(
                      color: const Color(0xFFB00020).withValues(alpha: 0.92),
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.all(10),
                        child: Text(
                          _error!,
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}
