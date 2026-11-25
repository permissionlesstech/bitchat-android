import 'package:flutter/material.dart';

import '../services/mascot_service.dart';
import 'offline_tile_preview_screen.dart';

class ShelterScreen extends StatefulWidget {
  const ShelterScreen({super.key});

  @override
  State<ShelterScreen> createState() => _ShelterScreenState();
}

class _ShelterScreenState extends State<ShelterScreen> with RouteAware {
  @override
  void initState() {
    super.initState();
    mascotOptionsNotifier.value = shelterOptions;
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
  }

  @override
  Widget build(BuildContext context) => const OfflineTilePreviewScreen();
}
