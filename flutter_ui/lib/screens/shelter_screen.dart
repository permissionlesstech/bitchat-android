import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';

import '../services/location_service.dart';
import '../services/mascot_service.dart';
import '../services/offline_shelter_service.dart';
import 'offline_navigation_screen.dart';

class ShelterScreen extends StatefulWidget {
  const ShelterScreen({super.key});

  @override
  State<ShelterScreen> createState() => _ShelterScreenState();
}

class _ShelterScreenState extends State<ShelterScreen> with RouteAware {
  Position? _position;
  List<NearbyShelter> _nearbyShelters = const [];
  String? _error;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    mascotRouteObserver.subscribe(this, ModalRoute.of(context)!);
  }

  @override
  void dispose() {
    mascotRouteObserver.unsubscribe(this);
    super.dispose();
  }

  @override
  void didPush() {
    mascotOptionsNotifier.value = shelterOptions;
  }

  @override
  void didPopNext() {
    mascotOptionsNotifier.value = shelterOptions;
    _refresh();
  }

  Future<void> _refresh() async {
    if (!mounted) return;
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final position = await LocationService().getCurrentPosition();
      final shelters = await OfflineShelterService.instance.findNearby(
        latitude: position.latitude,
        longitude: position.longitude,
      );
      if (!mounted) return;
      setState(() {
        _position = position;
        _nearbyShelters = shelters;
        _isLoading = false;
      });
    } on LocationServiceException catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.message;
        _isLoading = false;
      });
    } catch (error) {
      debugPrint('Unable to load offline shelter map: $error');
      if (!mounted) return;
      setState(() {
        _error = '無法載入避難所地圖，請確認定位權限後重試。';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final position = _position;
    if (!_isLoading && position != null) {
      return OfflineNavigationScreen(
        position: position,
        shelters: _nearbyShelters,
        onRefresh: _refresh,
        isRefreshing: false,
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('防災避難所')),
      body: Center(
        child: _isLoading
            ? const Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircularProgressIndicator(),
                  SizedBox(height: 12),
                  Text('正在取得定位並載入避難所…'),
                ],
              )
            : Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.location_off_rounded, size: 42),
                    const SizedBox(height: 12),
                    Text(_error ?? '無法顯示地圖', textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: _refresh,
                      icon: const Icon(Icons.refresh_rounded),
                      label: const Text('重新定位'),
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}
