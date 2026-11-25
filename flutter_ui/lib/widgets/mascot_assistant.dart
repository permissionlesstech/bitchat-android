import 'package:flutter/material.dart';
import '../services/mascot_service.dart';

// ─── States ───────────────────────────────────────────────────────────────────

enum _MascotState { idle, greeting, talking }

// ─── Main widget ──────────────────────────────────────────────────────────────

class MascotAssistant extends StatefulWidget {
  const MascotAssistant({super.key});

  @override
  State<MascotAssistant> createState() => _MascotAssistantState();
}

class _MascotAssistantState extends State<MascotAssistant> {
  /// 對話泡泡底部離畫面底部的距離（在小助理頭上）。
  static const _bubbleBottom = 184.0;

  _MascotState _state = _MascotState.idle;
  MascotOption? _selected;

  String get _image {
    switch (_state) {
      case _MascotState.idle:
        return 'assets/images/mascot_stand.png';
      case _MascotState.greeting:
        return 'assets/images/mascot_hi.png';
      case _MascotState.talking:
        return 'assets/images/mascot_talking.png';
    }
  }

  void _tapMascot() {
    setState(() {
      _state = _MascotState.greeting;
      _selected = null;
    });
  }

  void _selectOption(MascotOption opt) {
    setState(() {
      _state = _MascotState.talking;
      _selected = opt;
    });
  }

  void _goBack() {
    setState(() {
      _state = _MascotState.greeting;
      _selected = null;
    });
  }

  void _close() {
    setState(() {
      _state = _MascotState.idle;
      _selected = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    // The user switched the assistant off in settings (#80).
    return ValueListenableBuilder<bool>(
      valueListenable: mascotEnabledNotifier,
      builder: (context, enabled, _) => enabled ? _buildMascot(context) : const SizedBox.shrink(),
    );
  }

  Widget _buildMascot(BuildContext context) {
    return ValueListenableBuilder<List<MascotOption>>(
      valueListenable: mascotOptionsNotifier,
      builder: (context, options, _) {
        // Hide entirely on screens with no defined options (login, register, etc.)
        // 鍵盤彈出時不顯示：小助理會被鍵盤蓋住，對話泡泡則會擋在輸入框上（#79）。
        // 狀態保留，鍵盤收起後照原樣出現。
        final media = MediaQuery.of(context);
        if (media.viewInsets.bottom > 0) return const SizedBox.shrink();

        if (options.isEmpty) {
          if (_state != _MascotState.idle) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) setState(() { _state = _MascotState.idle; _selected = null; });
            });
          }
          return const SizedBox.shrink();
        }

        return Stack(
          children: [
            // Speech bubble
            if (_state != _MascotState.idle && options.isNotEmpty)
              Positioned(
                key: const ValueKey('speech_bubble'),
                right: 62,
                bottom: _bubbleBottom,
                child: _SpeechBubble(
                  // 泡泡從小助理上方長到狀態列下方為止，內容再多就在泡泡裡捲動（#79）。
                  maxHeight: media.size.height - _bubbleBottom - media.padding.top - 8,
                  state: _state,
                  selected: _selected,
                  options: options,
                  onOptionTap: _selectOption,
                  onBack: _goBack,
                  onClose: _close,
                ),
              ),

            // Mascot character
            Positioned(
              key: const ValueKey('mascot_character'),
              right: _state == _MascotState.idle ? -11 : -8,
              bottom: 20,
              child: Stack(
                alignment: Alignment.bottomRight,
                children: [
                  IgnorePointer(
                    child: Image.asset(
                      _image,
                      width: 175,
                      height: 175,
                      fit: BoxFit.contain,
                      errorBuilder: (context, error, stack) => Container(
                        width: 175,
                        height: 175,
                        decoration: const BoxDecoration(
                          shape: BoxShape.circle,
                          color: Color(0xFFE8C99A),
                        ),
                        child: const Icon(
                          Icons.support_agent_rounded,
                          size: 64,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ),
                  GestureDetector(
                    onTap: options.isNotEmpty ? _tapMascot : null,
                    behavior: HitTestBehavior.opaque,
                    child: const SizedBox(width: 95, height: 150),
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

// ─── Speech bubble ────────────────────────────────────────────────────────────

class _SpeechBubble extends StatelessWidget {
  final double maxHeight;
  final _MascotState state;
  final MascotOption? selected;
  final List<MascotOption> options;
  final ValueChanged<MascotOption> onOptionTap;
  final VoidCallback onBack;
  final VoidCallback onClose;

  const _SpeechBubble({
    required this.maxHeight,
    required this.state,
    required this.selected,
    required this.options,
    required this.onOptionTap,
    required this.onBack,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {},
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: 248,
            constraints: BoxConstraints(maxHeight: maxHeight.clamp(0.0, double.infinity)),
            padding: const EdgeInsets.fromLTRB(16, 14, 14, 16),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(20),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.13),
                  blurRadius: 18,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: SingleChildScrollView(
              child: state == _MascotState.greeting
                  ? _Greeting(
                      options: options,
                      onOptionTap: onOptionTap,
                      onClose: onClose,
                    )
                  : _Talking(
                      option: selected,
                      onBack: onBack,
                      onClose: onClose,
                    ),
            ),
          ),
          // Tail pointing toward mascot (bottom-right)
          Positioned(
            right: 18,
            bottom: -11,
            child: CustomPaint(
              painter: _TailPainter(),
              size: const Size(20, 13),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Greeting content ─────────────────────────────────────────────────────────

class _Greeting extends StatelessWidget {
  final List<MascotOption> options;
  final ValueChanged<MascotOption> onOptionTap;
  final VoidCallback onClose;

  const _Greeting({
    required this.options,
    required this.onOptionTap,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Expanded(
              child: Text(
                '嗨！我是你的防災小助理 ✨',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: Color(0xFF3D2C1E),
                ),
              ),
            ),
            GestureDetector(
              onTap: onClose,
              child: const Padding(
                padding: EdgeInsets.only(left: 6),
                child: Icon(Icons.close_rounded,
                    size: 18, color: Color(0xFF8C7B6E)),
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        const Text(
          '有什麼需要幫助的嗎？',
          style: TextStyle(fontSize: 12, color: Color(0xFF8C7B6E)),
        ),
        const SizedBox(height: 12),
        ...options.map(
          (opt) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: GestureDetector(
              onTap: () => onOptionTap(opt),
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: const Color(0xFFF7F3EC),
                  borderRadius: BorderRadius.circular(12),
                  border:
                      Border.all(color: const Color(0xFFD4C5B0), width: 1),
                ),
                child: Row(
                  children: [
                    Text(opt.icon, style: const TextStyle(fontSize: 15)),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        opt.label,
                        style: const TextStyle(
                          fontSize: 13,
                          color: Color(0xFF3D2C1E),
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                    const Icon(Icons.chevron_right_rounded,
                        size: 16, color: Color(0xFF8C7B6E)),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

// ─── Talking content ──────────────────────────────────────────────────────────

class _Talking extends StatelessWidget {
  final MascotOption? option;
  final VoidCallback onBack;
  final VoidCallback onClose;

  const _Talking(
      {required this.option, required this.onBack, required this.onClose});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          children: [
            GestureDetector(
              onTap: onBack,
              child: const Padding(
                padding: EdgeInsets.only(right: 4),
                child: Icon(Icons.arrow_back_ios_new_rounded,
                    size: 15, color: Color(0xFF8C7B6E)),
              ),
            ),
            Expanded(
              child: Text(
                '${option?.icon ?? ''} ${option?.label ?? ''}',
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: Color(0xFF3D2C1E),
                ),
              ),
            ),
            GestureDetector(
              onTap: onClose,
              child: const Padding(
                padding: EdgeInsets.only(left: 6),
                child: Icon(Icons.close_rounded,
                    size: 18, color: Color(0xFF8C7B6E)),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
            height: 1,
            color: const Color(0xFF3D2C1E).withValues(alpha: 0.07)),
        const SizedBox(height: 10),
        Text(
          option?.response ?? '',
          style: const TextStyle(
            fontSize: 13,
            height: 1.65,
            color: Color(0xFF3D2C1E),
          ),
        ),
      ],
    );
  }
}

// ─── Bubble tail painter ──────────────────────────────────────────────────────

class _TailPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final shadow = Paint()
      ..color = Colors.black.withValues(alpha: 0.07)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 2);
    final fill = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;

    final path = Path()
      ..moveTo(0, 0)
      ..lineTo(size.width, 0)
      ..lineTo(size.width * 0.75, size.height)
      ..close();

    canvas.drawPath(path, shadow);
    canvas.drawPath(path, fill);
  }

  @override
  bool shouldRepaint(_TailPainter old) => false;
}
