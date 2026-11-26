import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:geolocator/geolocator.dart';
import 'package:url_launcher/url_launcher.dart';

import '../services/location_service.dart';
import '../services/offline_public_facility_service.dart';
import '../services/offline_shelter_service.dart';
import '../services/taiwan_mbtiles_source.dart';

class OfflineTilePreviewScreen extends StatefulWidget {
  const OfflineTilePreviewScreen({super.key});

  @override
  State<OfflineTilePreviewScreen> createState() =>
      _OfflineTilePreviewScreenState();
}

class _OfflineTilePreviewScreenState extends State<OfflineTilePreviewScreen> {
  static const _minZoom = 10.0;
  static const _maxZoom = 19.0;
  static const _initialLatitude = 25.033;
  static const _initialLongitude = 121.5654;

  final TextEditingController _searchController = TextEditingController();
  final Map<int, Offset> _activePointers = {};
  final ValueNotifier<Offset> _dragOffset = ValueNotifier(Offset.zero);
  final LinkedHashMap<String, Future<Uint8List?>> _tileLoads = LinkedHashMap();

  TaiwanMbtilesSource? _source;
  StreamSubscription<Position>? _positionSubscription;
  Object? _loadError;
  bool _locationLoading = false;
  String? _locationError;
  Position? _position;
  double _centerLatitude = _initialLatitude;
  double _centerLongitude = _initialLongitude;
  double _zoom = 15;
  Size _mapViewportSize = Size.zero;
  bool _mapViewUpdateScheduled = false;
  String _searchQuery = '';
  PublicFacilityType? _facilityTypeFilter;
  List<NearbyShelter> _nearbyShelters = const [];
  List<NearbyPublicFacility> _nearbyFacilities = const [];
  NearbyShelter? _selectedShelter;
  NearbyPublicFacility? _selectedFacility;

  @override
  void initState() {
    super.initState();
    unawaited(_openSource());
    unawaited(_refreshLocation());
  }

  @override
  void dispose() {
    _searchController.dispose();
    _dragOffset.dispose();
    unawaited(_positionSubscription?.cancel());
    unawaited(_source?.close());
    super.dispose();
  }

  Future<void> _openSource() async {
    setState(() {
      _loadError = null;
    });
    try {
      final source = await TaiwanMbtilesSource.openFromAppFiles().timeout(
        const Duration(seconds: 12),
      );
      if (!mounted) {
        await source.close();
        return;
      }
      setState(() {
        _source = source;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loadError = error;
      });
    }
  }

  Future<void> _refreshLocation() async {
    if (!mounted) return;
    setState(() {
      _locationLoading = true;
      _locationError = null;
    });
    try {
      final position = await LocationService().getCurrentPosition();
      if (!mounted) return;
      await _applyPosition(position, recenter: true);
      _positionSubscription?.cancel();
      _positionSubscription = Geolocator.getPositionStream(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          distanceFilter: 5,
        ),
      ).listen((position) => unawaited(_applyPosition(position)));
    } on LocationServiceException catch (error) {
      if (!mounted) return;
      setState(() {
        _locationLoading = false;
        _locationError = error.message;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _locationLoading = false;
        _locationError = '無法取得目前定位：$error';
      });
    }
  }

  Future<void> _applyPosition(
    Position position, {
    bool recenter = false,
  }) async {
    if (!mounted) return;
    final shelters = await OfflineShelterService.instance.findNearby(
      latitude: position.latitude,
      longitude: position.longitude,
    );
    final facilities = await OfflinePublicFacilityService.instance.findNearby(
      latitude: position.latitude,
      longitude: position.longitude,
    );
    if (!mounted) return;
    setState(() {
      _position = position;
      _locationLoading = false;
      _locationError = null;
      _nearbyShelters = shelters.take(15).toList(growable: false);
      _nearbyFacilities = facilities;
      if (recenter) {
        _centerLatitude = position.latitude;
        _centerLongitude = position.longitude;
        _zoom = 15;
      }
    });
  }

  int get _sourceZoom => _zoom.round().clamp(1, 15).toInt();
  double get _sourceScale => _zoom >= 15
      ? math.pow(2, (_zoom.round() - 15).clamp(0, 4)).toDouble()
      : 1;

  double _mercatorY(double latitude) {
    final value = latitude.clamp(-85.05112878, 85.05112878) * math.pi / 180;
    return (1 - math.log(math.tan(value) + 1 / math.cos(value)) / math.pi) / 2;
  }

  Offset _worldPosition(double latitude, double longitude) {
    final tileCount = 1 << _sourceZoom;
    final worldPixels = tileCount * 256.0 * _sourceScale;
    return Offset(
      (longitude + 180) / 360 * worldPixels,
      _mercatorY(latitude) * worldPixels,
    );
  }

  Offset _projectLocation(double latitude, double longitude) {
    final point = _worldPosition(latitude, longitude);
    final center = _worldPosition(_centerLatitude, _centerLongitude);
    var deltaX = point.dx - center.dx;
    final worldWidth = (1 << _sourceZoom) * 256.0 * _sourceScale;
    if (deltaX > worldWidth / 2) deltaX -= worldWidth;
    if (deltaX < -worldWidth / 2) deltaX += worldWidth;
    return Offset(
      _mapViewportSize.width / 2 + deltaX,
      _mapViewportSize.height / 2 + point.dy - center.dy,
    );
  }

  Future<Uint8List?> _tileData(int zoom, int column, int row) {
    final source = _source;
    if (source == null) return Future.value(null);
    final key = '$zoom:$column:$row';
    final cached = _tileLoads[key];
    if (cached != null) return cached;
    final future = source.getTile(zoom, column, row);
    _tileLoads[key] = future;
    if (_tileLoads.length >= 256) {
      _tileLoads.remove(_tileLoads.keys.first);
    }
    return future;
  }

  List<_VisibleTile> _visibleTiles() {
    final zoom = _sourceZoom;
    final tileCount = 1 << zoom;
    final scale = _sourceScale;
    final tileSize = 256.0 * scale;
    final center = _worldPosition(_centerLatitude, _centerLongitude);
    final halfWidth = _mapViewportSize.width / 2;
    final halfHeight = _mapViewportSize.height / 2;
    final firstColumn = ((center.dx - halfWidth) / tileSize).floor() - 1;
    final lastColumn = ((center.dx + halfWidth) / tileSize).floor() + 1;
    final firstRow = ((center.dy - halfHeight) / tileSize).floor() - 1;
    final lastRow = ((center.dy + halfHeight) / tileSize).floor() + 1;
    final result = <_VisibleTile>[];

    for (
      var worldColumn = firstColumn;
      worldColumn <= lastColumn;
      worldColumn++
    ) {
      final column = (worldColumn % tileCount + tileCount) % tileCount;
      for (var row = firstRow; row <= lastRow; row++) {
        if (row < 0 || row >= tileCount) continue;
        result.add(
          _VisibleTile(
            zoom: zoom,
            column: column,
            row: row,
            left: worldColumn * tileSize - center.dx + halfWidth,
            top: row * tileSize - center.dy + halfHeight,
            size: tileSize,
          ),
        );
      }
    }
    return result;
  }

  void _scheduleMapUpdate(VoidCallback update) {
    update();
    if (_mapViewUpdateScheduled) return;
    _mapViewUpdateScheduled = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _mapViewUpdateScheduled = false;
      if (!mounted) return;
      setState(() {});
    });
  }

  void _onPointerDown(PointerDownEvent event) {
    if (_activePointers.isNotEmpty) _commitDrag();
    _activePointers[event.pointer] = event.localPosition;
  }

  void _onPointerMove(PointerMoveEvent event) {
    final previous = _activePointers[event.pointer];
    if (previous == null) return;

    if (_activePointers.length >= 2) {
      final other = _activePointers.entries
          .where((entry) => entry.key != event.pointer)
          .firstOrNull;
      if (other != null) {
        final previousDistance = (previous - other.value).distance;
        final nextDistance = (event.localPosition - other.value).distance;
        if (previousDistance > 0 && nextDistance > 0) {
          final nextZoom =
              (_zoom + math.log(nextDistance / previousDistance) / math.ln2)
                  .clamp(_minZoom, _maxZoom);
          _scheduleMapUpdate(() {
            _zoom = nextZoom.toDouble();
          });
        }
      }
    } else {
      final dx = event.localPosition.dx - previous.dx;
      final dy = event.localPosition.dy - previous.dy;
      _dragOffset.value += Offset(dx, dy);
    }
    _activePointers[event.pointer] = event.localPosition;
  }

  void _onPointerUp(PointerUpEvent event) {
    _activePointers.remove(event.pointer);
    if (_activePointers.isEmpty) _commitDrag();
  }

  void _onPointerCancel(PointerCancelEvent event) {
    _activePointers.remove(event.pointer);
    if (_activePointers.isEmpty) _commitDrag();
  }

  void _commitDrag() {
    final offset = _dragOffset.value;
    if (offset == Offset.zero) return;

    final worldSize = (1 << _sourceZoom) * 256.0 * _sourceScale;
    final longitudeDelta = -offset.dx / worldSize * 360;
    final centerY = _mercatorY(_centerLatitude);
    final nextY = (centerY - offset.dy / worldSize).clamp(0.0, 1.0);
    final mercator = math.pi * (1 - 2 * nextY);
    final latitude =
        (2 * math.atan(math.exp(mercator)) - math.pi / 2) * 180 / math.pi;
    setState(() {
      _centerLongitude =
          ((_centerLongitude + longitudeDelta + 540) % 360) - 180;
      _centerLatitude = latitude;
      _dragOffset.value = Offset.zero;
    });
  }

  void _changeZoom(int delta) {
    setState(() {
      _zoom = (_zoom.round() + delta).clamp(_minZoom, _maxZoom).toDouble();
    });
  }

  void _recenter() {
    setState(() {
      if (_position != null) {
        _centerLatitude = _position!.latitude;
        _centerLongitude = _position!.longitude;
      } else {
        _centerLatitude = _initialLatitude;
        _centerLongitude = _initialLongitude;
      }
      _zoom = 15;
    });
  }

  void _onSearchChanged(String query) {
    setState(() => _searchQuery = query);
  }

  void _selectFacilityType(PublicFacilityType? type) {
    setState(() {
      _facilityTypeFilter = type;
      _selectedFacility = null;
    });
  }

  List<NearbyPublicFacility> get _filteredFacilities {
    final query = _searchQuery.trim().toLowerCase();
    var facilities = _nearbyFacilities;
    if (_facilityTypeFilter != null) {
      facilities = facilities
          .where((facility) => facility.location.type == _facilityTypeFilter)
          .toList(growable: false);
    }
    if (query.isNotEmpty) {
      final nameMatches = facilities
          .where(
            (facility) =>
                facility.location.name.toLowerCase().contains(query) ||
                facility.location.address.toLowerCase().contains(query),
          )
          .toList(growable: false);
      if (nameMatches.isNotEmpty) facilities = nameMatches;
    }
    return facilities.take(40).toList(growable: false);
  }

  List<NearbyShelter> get _searchedShelters {
    final query = _searchQuery.trim().toLowerCase();
    if (query.isEmpty) return _nearbyShelters;
    return _nearbyShelters
        .where((shelter) => shelter.location.name.toLowerCase().contains(query))
        .take(15)
        .toList(growable: false);
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

  Future<void> _showShelterDetails(NearbyShelter shelter) async {
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
              Text(
                shelter.distanceMeters < 1000
                    ? '距離目前位置 ${shelter.distanceMeters.round()} 公尺'
                    : '距離目前位置 ${(shelter.distanceMeters / 1000).toStringAsFixed(1)} 公里',
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
                  Text(_facilityTypeLabel(facility.location.type)),
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
              Text(
                facility.distanceMeters < 1000
                    ? '距離目前位置 ${facility.distanceMeters.round()} 公尺'
                    : '距離目前位置 ${(facility.distanceMeters / 1000).toStringAsFixed(1)} 公里',
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _showDataSources() async {
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('資料來源與授權'),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _dataSourceItem(
                  title: '離線底圖',
                  details:
                      '內政部國土測繪中心電子地圖資料供應服務。'
                      '正式再散布條件及產品署名要求尚待確認。',
                  uri: Uri.parse(
                    'https://maps.nlsc.gov.tw/MbIndex_qryPage.action?fun=8',
                  ),
                ),
                _dataSourceItem(
                  title: '防空避難所',
                  details:
                      '警政署防空疏散避難設施資料，由 kiang/npa.gov.tw 彙整。'
                      'GeoJSON 最近資料更新 commit：2025-06-19。'
                      'MIT 僅適用於該專案程式碼；原始資料授權尚未確認。',
                  uri: Uri.parse('https://github.com/kiang/npa.gov.tw'),
                ),
                _dataSourceItem(
                  title: '警察局',
                  details:
                      '警政署資料集 #5958，政府資料開放授權條款第 1 版。'
                      '來源頁 metadata 更新日期：2026-11-06；'
                      '來源檔 PoliceAddress1_1151106.csv。',
                  uri: Uri.parse('https://data.gov.tw/dataset/5958'),
                ),
                _dataSourceItem(
                  title: '消防局',
                  details:
                      '消防署資料集 #5969，政府資料開放授權條款第 1 版。'
                      '來源頁 metadata 更新日期：2025-12-16；'
                      '提供檔案於 2026-11-17 下載。',
                  uri: Uri.parse('https://data.gov.tw/dataset/5969'),
                ),
                _dataSourceItem(
                  title: '公車站',
                  details:
                      '交通部 TDX GIS-T，使用條款要求標示原資料提供機關。'
                      '提供的快照日期為 2026-10-01，但檔案 Updatetime '
                      '為 2026-10-28 至 2026-10-30，日期尚待確認。'
                      '本機檔案於 2026-11-17 下載。',
                  uri: Uri.parse('https://tdx.transportdata.tw/'),
                ),
                _dataSourceItem(
                  title: '醫療機構',
                  details:
                      '衛福部資料集 #15393，政府資料開放授權條款第 1 版。'
                      'ODS 版本日期 2025-12-31，來源頁 metadata 更新日期 '
                      '2025-08-29。資料沒有經緯度，無法產生可靠標記，'
                      '因此地圖不顯示醫院。',
                  uri: Uri.parse('https://data.gov.tw/dataset/15393'),
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('關閉'),
          ),
        ],
      ),
    );
  }

  Widget _dataSourceItem({
    required String title,
    required String details,
    required Uri uri,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text(details),
          TextButton.icon(
            onPressed: () => _openDataSource(uri),
            icon: const Icon(Icons.open_in_new_rounded, size: 16),
            label: const Text('開啟來源'),
          ),
        ],
      ),
    );
  }

  Future<void> _openDataSource(Uri uri) async {
    try {
      if (await launchUrl(uri, mode: LaunchMode.externalApplication)) return;
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('無法開啟資料來源：$error')));
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('無法開啟資料來源連結')));
  }

  Widget _buildControls() {
    final shelters = _searchedShelters;
    return Positioned(
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
                      '附近避難所 ${shelters.length} / ${_nearbyShelters.length} 處',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  if (_locationLoading)
                    const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                ],
              ),
              const SizedBox(height: 6),
              TextField(
                controller: _searchController,
                key: const ValueKey('public-facility-search'),
                onChanged: _onSearchChanged,
                decoration: InputDecoration(
                  labelText: '搜尋附近避難所、消防局、警察局、車站或公車站',
                  hintText: '輸入避難所或公共設施名稱',
                  prefixIcon: const Icon(Icons.search_rounded),
                  suffixIcon: _searchQuery.isEmpty
                      ? null
                      : IconButton(
                          onPressed: () {
                            _searchController.clear();
                            _onSearchChanged('');
                          },
                          icon: const Icon(Icons.close_rounded),
                        ),
                  isDense: true,
                  filled: true,
                  fillColor: Colors.white,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
              ),
              const SizedBox(height: 6),
              SizedBox(
                height: 38,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  children: [
                    ChoiceChip(
                      label: const Text('全部'),
                      selected: _facilityTypeFilter == null,
                      onSelected: (_) => _selectFacilityType(null),
                    ),
                    const SizedBox(width: 6),
                    for (final type in const [
                      PublicFacilityType.fireStation,
                      PublicFacilityType.policeStation,
                      PublicFacilityType.station,
                      PublicFacilityType.busStop,
                    ]) ...[
                      ChoiceChip(
                        avatar: Icon(
                          _facilityIcon(type),
                          size: 16,
                          color: _facilityColor(type),
                        ),
                        label: Text(_facilityTypeLabel(type)),
                        selected: _facilityTypeFilter == type,
                        onSelected: (_) => _selectFacilityType(type),
                      ),
                      const SizedBox(width: 6),
                    ],
                  ],
                ),
              ),
              if (_locationError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    _locationError!,
                    style: const TextStyle(
                      color: Color(0xFF9A6700),
                      fontSize: 12,
                    ),
                  ),
                ),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  key: const ValueKey('offline-map-data-sources'),
                  onPressed: _showDataSources,
                  icon: const Icon(Icons.info_outline_rounded, size: 18),
                  label: const Text('資料來源與授權'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMap(Size size) {
    _mapViewportSize = size;
    final shelters = _searchedShelters;
    final facilities = _filteredFacilities;
    final mapLayer = RepaintBoundary(
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          for (final tile in _visibleTiles())
            Positioned(
              key: ValueKey('tile-${tile.zoom}-${tile.column}-${tile.row}'),
              left: tile.left,
              top: tile.top,
              width: tile.size,
              height: tile.size,
              child: RepaintBoundary(
                child: FutureBuilder<Uint8List?>(
                  key: ValueKey(
                    'tile-future-${tile.zoom}-${tile.column}-${tile.row}',
                  ),
                  future: _tileData(tile.zoom, tile.column, tile.row),
                  builder: (context, snapshot) {
                    final data = snapshot.data;
                    if (snapshot.hasError) {
                      return const ColoredBox(color: Color(0xFFFFE6E6));
                    }
                    if (data == null) {
                      return const ColoredBox(color: Color(0xFFE7E9E7));
                    }
                    return Image.memory(
                      data,
                      fit: BoxFit.fill,
                      filterQuality: FilterQuality.low,
                      gaplessPlayback: true,
                    );
                  },
                ),
              ),
            ),
          if (_position != null)
            Builder(
              builder: (context) {
                final point = _projectLocation(
                  _position!.latitude,
                  _position!.longitude,
                );
                if (point.dx < -30 ||
                    point.dx > size.width + 30 ||
                    point.dy < 100 ||
                    point.dy > size.height + 30) {
                  return const SizedBox.shrink();
                }
                return Positioned(
                  left: point.dx - 18,
                  top: point.dy - 18,
                  width: 36,
                  height: 36,
                  child: Transform.rotate(
                    angle:
                        ((_position!.heading.isFinite && _position!.heading >= 0
                            ? _position!.heading
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
                );
              },
            ),
          for (final shelter in shelters)
            Builder(
              builder: (context) {
                final point = _projectLocation(
                  shelter.location.latitude,
                  shelter.location.longitude,
                );
                if (point.dx < -30 ||
                    point.dx > size.width + 30 ||
                    point.dy < 120 ||
                    point.dy > size.height + 30) {
                  return const SizedBox.shrink();
                }
                final selected = identical(_selectedShelter, shelter);
                return Positioned(
                  left: point.dx - 20,
                  top: point.dy - 40,
                  width: 40,
                  height: 44,
                  child: GestureDetector(
                    onTap: () {
                      setState(() {
                        _selectedShelter = shelter;
                        _centerLatitude = shelter.location.latitude;
                        _centerLongitude = shelter.location.longitude;
                        _zoom = 17;
                      });
                      unawaited(_showShelterDetails(shelter));
                    },
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
          for (final facility in facilities)
            Builder(
              builder: (context) {
                final point = _projectLocation(
                  facility.location.latitude,
                  facility.location.longitude,
                );
                if (point.dx < -30 ||
                    point.dx > size.width + 30 ||
                    point.dy < 260 ||
                    point.dy > size.height + 30) {
                  return const SizedBox.shrink();
                }
                final selected = identical(_selectedFacility, facility);
                return Positioned(
                  left: point.dx - 18,
                  top: point.dy - 20,
                  width: 36,
                  height: 40,
                  child: GestureDetector(
                    onTap: () {
                      setState(() {
                        _selectedFacility = facility;
                        _centerLatitude = facility.location.latitude;
                        _centerLongitude = facility.location.longitude;
                        _zoom = 17;
                      });
                      unawaited(_showFacilityDetails(facility));
                    },
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
        ],
      ),
    );
    return Stack(
      clipBehavior: Clip.hardEdge,
      children: [
        Positioned.fill(
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: _onPointerDown,
            onPointerMove: _onPointerMove,
            onPointerUp: _onPointerUp,
            onPointerCancel: _onPointerCancel,
            child: ValueListenableBuilder<Offset>(
              key: const ValueKey('offline-map-drag-layer'),
              valueListenable: _dragOffset,
              child: mapLayer,
              builder: (context, offset, child) =>
                  Transform.translate(offset: offset, child: child),
            ),
          ),
        ),
        Positioned(
          right: 12,
          top: 286,
          child: Column(
            children: [
              FloatingActionButton.small(
                heroTag: 'tile_preview_zoom_in',
                onPressed: _zoom < _maxZoom ? () => _changeZoom(1) : null,
                tooltip: '放大',
                child: const Icon(Icons.add),
              ),
              const SizedBox(height: 8),
              FloatingActionButton.small(
                heroTag: 'tile_preview_zoom_out',
                onPressed: _zoom > _minZoom ? () => _changeZoom(-1) : null,
                tooltip: '縮小',
                child: const Icon(Icons.remove),
              ),
              const SizedBox(height: 8),
              FloatingActionButton.small(
                heroTag: 'tile_preview_recenter',
                onPressed: _recenter,
                tooltip: '回到目前位置',
                child: const Icon(Icons.navigation_rounded),
              ),
            ],
          ),
        ),
        Positioned(
          left: 12,
          bottom: 12,
          child: Material(
            color: Colors.white.withValues(alpha: 0.92),
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: Text(
                '離線 MBTiles · EPSG:3857 · L${_zoom.round()} / $_maxZoom',
                style: const TextStyle(fontSize: 12),
              ),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('防災避難所・離線地圖'),
        actions: [
          IconButton(
            onPressed: _refreshLocation,
            tooltip: '重新定位',
            icon: _locationLoading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.my_location_rounded),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          LayoutBuilder(
            key: const ValueKey('offline-map-viewport'),
            builder: (context, constraints) => _buildMap(constraints.biggest),
          ),
          _buildControls(),
          if (_loadError != null)
            Positioned(
              left: 12,
              right: 12,
              bottom: 52,
              child: Material(
                color: Colors.white.withValues(alpha: 0.96),
                borderRadius: BorderRadius.circular(10),
                child: Padding(
                  padding: const EdgeInsets.all(10),
                  child: Row(
                    children: [
                      const Icon(Icons.warning_amber_rounded),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '瓦片載入失敗：請確認 '
                          '${TaiwanMbtilesSource.fileName} 已放入 '
                          '${TaiwanMbtilesSource.directoryName}',
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                      IconButton(
                        onPressed: _openSource,
                        tooltip: '重試',
                        icon: const Icon(Icons.refresh),
                      ),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _VisibleTile {
  const _VisibleTile({
    required this.zoom,
    required this.column,
    required this.row,
    required this.left,
    required this.top,
    required this.size,
  });

  final int zoom;
  final int column;
  final int row;
  final double left;
  final double top;
  final double size;
}
