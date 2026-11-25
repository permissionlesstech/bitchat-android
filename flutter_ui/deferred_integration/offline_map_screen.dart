import 'dart:async';
import 'dart:math' as math;

import 'package:agus_maps_flutter/agus_maps_flutter.dart' as maps;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import '../services/offline_map_camera.dart';
import '../services/offline_public_facility_service.dart';
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

class OfflineMapScreen extends StatefulWidget {
  const OfflineMapScreen({
    super.key,
    required this.position,
    required this.shelters,
    this.publicFacilities = const [],
    this.publicFacilityError,
    this.onRefresh,
    this.isRefreshing = false,
  });

  final Position position;
  final List<NearbyShelter> shelters;
  final List<NearbyPublicFacility> publicFacilities;
  final String? publicFacilityError;
  final Future<void> Function()? onRefresh;
  final bool isRefreshing;

  @override
  State<OfflineMapScreen> createState() => _OfflineMapScreenState();
}

class _OfflineMapScreenState extends State<OfflineMapScreen> {
  late final TextEditingController _facilitySearchController;
  bool _dataReady = false;
  String? _error;
  List<String> _mapPaths = const [];
  int? _mapTextureId;
  bool _surfaceCreationStarted = false;
  double? _surfaceDensity;
  Size _mapViewportSize = Size.zero;
  final Map<int, Offset> _activePointers = {};
  bool _mapViewUpdateScheduled = false;
  static const _maxVisibleShelters = 15;
  late Position _position = widget.position;
  late double _centerLat = widget.position.latitude;
  late double _centerLon = widget.position.longitude;
  late double _zoom = 17;
  late int _visibleShelterCount = math.min(
    _maxVisibleShelters,
    widget.shelters.length,
  );
  String _facilitySearchQuery = '';
  NearbyShelter? _selectedShelter;
  NearbyPublicFacility? _selectedFacility;
  StreamSubscription<Position>? _positionSubscription;

  @override
  void didUpdateWidget(OfflineMapScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.position != widget.position) {
      _position = widget.position;
    }
    if (oldWidget.shelters != widget.shelters) {
      _visibleShelterCount = math.min(
        _maxVisibleShelters,
        widget.shelters.length,
      );
    }
  }

  @override
  void initState() {
    super.initState();
    _facilitySearchController = TextEditingController();
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
          },
        );
    SchedulerBinding.instance.addPostFrameCallback((_) {
      unawaited(_initializeMap());
    });
  }

  @override
  void dispose() {
    _facilitySearchController.dispose();
    unawaited(_positionSubscription?.cancel());
    super.dispose();
  }

  List<NearbyShelter> get _visibleShelters =>
      widget.shelters.take(_visibleShelterCount).toList(growable: false);

  List<NearbyPublicFacility> get _visibleFacilities {
    final searched = OfflinePublicFacilityService.searchEmergencyFacilities(
      widget.publicFacilities,
      query: _facilitySearchQuery,
    );
    final facilities = _facilitySearchQuery.trim().isEmpty
        ? widget.publicFacilities
        : searched;
    return facilities.take(40).toList(growable: false);
  }

  List<NearbyPublicFacility> get _facilitySearchResults =>
      _facilitySearchQuery.trim().isEmpty
      ? const []
      : OfflinePublicFacilityService.searchEmergencyFacilities(
          widget.publicFacilities,
          query: _facilitySearchQuery,
        ).take(8).toList(growable: false);

  void _selectFacility(NearbyPublicFacility facility) {
    setState(() {
      _selectedFacility = facility;
      _centerLat = facility.location.latitude;
      _centerLon = facility.location.longitude;
      _zoom = 17;
    });
    if (_mapTextureId != null) {
      maps.setView(_centerLat, _centerLon, _zoom.round());
    }
    unawaited(_showFacilityDetails(facility));
  }

  void _onFacilitySearchChanged(String query) {
    setState(() {
      _facilitySearchQuery = query;
      if (_selectedFacility != null &&
          !OfflinePublicFacilityService.searchEmergencyFacilities(
            widget.publicFacilities,
            query: query,
          ).contains(_selectedFacility)) {
        _selectedFacility = null;
      }
    });
  }

  IconData _facilityIcon(PublicFacilityType type) => switch (type) {
    PublicFacilityType.hospital => Icons.local_hospital_rounded,
    PublicFacilityType.station => Icons.train_rounded,
    PublicFacilityType.busStop => Icons.directions_bus_rounded,
    PublicFacilityType.fireStation => Icons.local_fire_department_rounded,
    PublicFacilityType.policeStation => Icons.local_police_rounded,
  };

  Color _facilityColor(PublicFacilityType type) => switch (type) {
    PublicFacilityType.hospital => const Color(0xFFC62828),
    PublicFacilityType.station => const Color(0xFF1565C0),
    PublicFacilityType.busStop => const Color(0xFF008577),
    PublicFacilityType.fireStation => const Color(0xFFEF6C00),
    PublicFacilityType.policeStation => const Color(0xFF283593),
  };

  String _facilityTypeLabel(PublicFacilityType type) => switch (type) {
    PublicFacilityType.hospital => '醫院',
    PublicFacilityType.station => '車站',
    PublicFacilityType.busStop => '公車站',
    PublicFacilityType.fireStation => '消防局',
    PublicFacilityType.policeStation => '警察局',
  };

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

  void _onPointerDown(PointerDownEvent event) {
    _activePointers[event.pointer] = event.localPosition;
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
  }

  void _updateMapCenterForGesture(
    Offset previousPosition,
    Offset position, {
    Offset? otherPointerPosition,
  }) {
    final camera =
        OfflineMapCamera(
          latitude: _centerLat,
          longitude: _centerLon,
          zoom: _zoom,
        ).updateForGesture(
          previousPosition: previousPosition,
          position: position,
          otherPointerPosition: otherPointerPosition,
          viewportSize: _mapViewportSize,
          devicePixelRatio:
              _surfaceDensity ?? MediaQuery.devicePixelRatioOf(context),
        );
    setState(() {
      _centerLat = camera.latitude;
      _centerLon = camera.longitude;
      _zoom = camera.zoom;
    });
    _scheduleMapViewUpdate();
  }

  void _scheduleMapViewUpdate() {
    if (_mapViewUpdateScheduled || _mapTextureId == null) return;
    _mapViewUpdateScheduled = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _mapViewUpdateScheduled = false;
      if (!mounted || _mapTextureId == null) return;
      maps.setView(_centerLat, _centerLon, _zoom.round());
    });
  }

  void _onPointerUp(PointerUpEvent event) {
    _activePointers.remove(event.pointer);
  }

  void _onPointerCancel(PointerCancelEvent event) {
    _activePointers.remove(event.pointer);
  }

  void _recenter() {
    setState(() {
      _centerLat = _position.latitude;
      _centerLon = _position.longitude;
      _zoom = 17;
    });
    if (_mapTextureId != null) {
      maps.setView(_centerLat, _centerLon, _zoom.round());
    }
  }

  void _selectShelter(NearbyShelter shelter, {int zoom = 16}) {
    setState(() {
      _selectedShelter = shelter;
      _centerLat = shelter.location.latitude;
      _centerLon = shelter.location.longitude;
      _zoom = zoom.toDouble();
    });
    if (_mapTextureId != null) {
      maps.setView(_centerLat, _centerLon, _zoom.round());
    }
  }

  void _onPositionChanged(Position position) {
    if (!mounted) return;
    setState(() => _position = position);
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
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _showFacilityDetails(NearbyPublicFacility facility) async {
    final distance = facility.distanceMeters < 1000
        ? '${facility.distanceMeters.round()} 公尺'
        : '${(facility.distanceMeters / 1000).toStringAsFixed(1)} 公里';
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
              Row(
                children: [
                  Icon(
                    _facilityIcon(facility.location.type),
                    color: _facilityColor(facility.location.type),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    _facilityTypeLabel(facility.location.type),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                facility.location.name,
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              if (facility.location.address.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(facility.location.address),
              ],
              const SizedBox(height: 8),
              Row(
                children: [
                  const Icon(Icons.social_distance_rounded, size: 20),
                  const SizedBox(width: 8),
                  Text('距離目前位置 $distance'),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Offset _projectToMap(double lat, double lon, Size size) {
    return OfflineMapCamera(
      latitude: _centerLat,
      longitude: _centerLon,
      zoom: _zoom,
    ).project(
      lat,
      lon,
      size,
      _surfaceDensity ?? MediaQuery.devicePixelRatioOf(context),
    );
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
          final visibleFacilities = _visibleFacilities;
          final facilitySearchResults = _facilitySearchResults;
          final userPoint = _projectToMap(
            _position.latitude,
            _position.longitude,
            mapSize,
          );
          final controlsPanel = Positioned(
            key: const ValueKey('offline-map-controls'),
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
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    SizedBox(
                      height: 52,
                      child: TextField(
                        key: const ValueKey('public-facility-search'),
                        controller: _facilitySearchController,
                        textInputAction: TextInputAction.search,
                        onChanged: _onFacilitySearchChanged,
                        decoration: InputDecoration(
                          labelText: '搜尋附近醫院、消防局或警察局',
                          hintText: '輸入設施類別或名稱',
                          prefixIcon: const Icon(Icons.search_rounded),
                          suffixIcon: _facilitySearchQuery.isEmpty
                              ? null
                              : IconButton(
                                  onPressed: () {
                                    _facilitySearchController.clear();
                                    _onFacilitySearchChanged('');
                                  },
                                  tooltip: '清除搜尋',
                                  icon: const Icon(Icons.close_rounded),
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
                    if (_facilitySearchQuery.trim().isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        facilitySearchResults.isEmpty
                            ? '找不到符合的附近醫院、消防局或警察局'
                            : '搜尋結果 ${facilitySearchResults.length} 處',
                        style: const TextStyle(fontSize: 12),
                      ),
                      if (facilitySearchResults.isNotEmpty)
                        SizedBox(
                          height: 38,
                          child: ListView.separated(
                            scrollDirection: Axis.horizontal,
                            itemCount: facilitySearchResults.length,
                            separatorBuilder: (_, _) =>
                                const SizedBox(width: 6),
                            itemBuilder: (_, index) {
                              final facility = facilitySearchResults[index];
                              final distance = facility.distanceMeters < 1000
                                  ? '${facility.distanceMeters.round()} m'
                                  : '${(facility.distanceMeters / 1000).toStringAsFixed(1)} km';
                              return ActionChip(
                                avatar: Icon(
                                  _facilityIcon(facility.location.type),
                                  size: 16,
                                  color: _facilityColor(facility.location.type),
                                ),
                                label: Text(
                                  '${facility.location.name} · $distance',
                                ),
                                onPressed: () => _selectFacility(facility),
                              );
                            },
                          ),
                        ),
                    ],
                    const SizedBox(height: 6),
                    SizedBox(
                      height: 38,
                      child: ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: visibleShelters.length,
                        separatorBuilder: (_, _) => const SizedBox(width: 6),
                        itemBuilder: (_, index) {
                          final shelter = visibleShelters[index];
                          final selected = identical(_selectedShelter, shelter);
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
                            label: Text('${shelter.location.name} · $distance'),
                            backgroundColor: selected
                                ? const Color(0xFFFFE0D4)
                                : Colors.white,
                            onPressed: () => _selectShelter(shelter, zoom: 17),
                          );
                        },
                      ),
                    ),
                    if (widget.publicFacilityError != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          widget.publicFacilityError!,
                          style: const TextStyle(
                            color: Color(0xFF9A6700),
                            fontSize: 12,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
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
                  right: 12,
                  top: 286,
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
                for (final facility in visibleFacilities)
                  Builder(
                    key: ValueKey(
                      'public-facility-layer-${facility.location.id}',
                    ),
                    builder: (context) {
                      final point = _projectToMap(
                        facility.location.latitude,
                        facility.location.longitude,
                        mapSize,
                      );
                      if (point.dx < -30 ||
                          point.dx > mapSize.width + 30 ||
                          point.dy < 260 ||
                          point.dy > mapSize.height + 30) {
                        return const SizedBox.shrink();
                      }
                      final selected = identical(_selectedFacility, facility);
                      return Positioned(
                        key: ValueKey(
                          'public-facility-marker-${facility.location.id}',
                        ),
                        left: point.dx - 18,
                        top: point.dy - 20,
                        width: 36,
                        height: 40,
                        child: GestureDetector(
                          onTap: () => _selectFacility(facility),
                          child: Icon(
                            _facilityIcon(facility.location.type),
                            size: selected ? 36 : 32,
                            color: _facilityColor(facility.location.type),
                            shadows: const [
                              Shadow(color: Colors.white, blurRadius: 4),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                controlsPanel,
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
