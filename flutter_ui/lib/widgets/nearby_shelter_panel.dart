import 'package:flutter/material.dart';

import '../services/offline_shelter_service.dart';

class NearbyShelterPanel extends StatelessWidget {
  const NearbyShelterPanel({
    super.key,
    required this.isLoading,
    required this.shelters,
    required this.onSelect,
    this.error,
    this.onRetry,
  });

  final bool isLoading;
  final List<NearbyShelter> shelters;
  final ValueChanged<NearbyShelter> onSelect;
  final String? error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      color: const Color(0xFFFEFDF9),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.health_and_safety_rounded,
                color: Color(0xFF5C3D2E),
                size: 20,
              ),
              const SizedBox(width: 6),
              Text(
                '附近避難所',
                style: theme.textTheme.titleSmall?.copyWith(
                  color: const Color(0xFF3D2C1E),
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '10 公里內・最近 5 處',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: const Color(0xFF8C7B6E),
                ),
              ),
              const Spacer(),
              if (onRetry != null)
                IconButton(
                  onPressed: isLoading ? null : onRetry,
                  tooltip: '重新定位',
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.my_location_rounded, size: 18),
                ),
            ],
          ),
          if (isLoading)
            const LinearProgressIndicator(minHeight: 2)
          else if (error != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 6, 4, 8),
              child: Text(error!, style: theme.textTheme.bodySmall),
            )
          else if (shelters.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 6, 4, 8),
              child: Text(
                '目前位置 10 公里內沒有收錄的避難所。',
                style: theme.textTheme.bodySmall,
              ),
            )
          else
            SizedBox(
              height: 88,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: shelters.length,
                separatorBuilder: (context, index) => const SizedBox(width: 8),
                itemBuilder: (context, index) {
                  final nearby = shelters[index];
                  return _NearbyShelterCard(
                    shelter: nearby,
                    onTap: () => onSelect(nearby),
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}

class _NearbyShelterCard extends StatelessWidget {
  const _NearbyShelterCard({required this.shelter, required this.onTap});

  final NearbyShelter shelter;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final distance = shelter.distanceMeters < 1000
        ? '${shelter.distanceMeters.round()} 公尺'
        : '${(shelter.distanceMeters / 1000).toStringAsFixed(1)} 公里';

    return SizedBox(
      width: 260,
      child: Card(
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Row(
              children: [
                const Icon(Icons.location_on_rounded, color: Color(0xFF6B9EAD)),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        shelter.location.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xFF3D2C1E),
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        '距離 $distance・步行導航',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Color(0xFF8C7B6E),
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                ),
                const Icon(
                  Icons.directions_walk_rounded,
                  size: 18,
                  color: Color(0xFF5C3D2E),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
