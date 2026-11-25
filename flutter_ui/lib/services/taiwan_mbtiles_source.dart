import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';

import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

class TaiwanMbtilesSource {
  TaiwanMbtilesSource._(this._database);

  static const fileName = 'Taiwan_Offline_Tiles.mbtiles';
  static const directoryName = 'offline_maps';
  static const maxCachedTiles = 128;

  final Database _database;
  final LinkedHashMap<String, Uint8List> _tileCache = LinkedHashMap();
  final Map<String, Future<Uint8List?>> _pendingTiles = {};

  static Future<TaiwanMbtilesSource> openFromAppFiles() async {
    final directory = await getExternalStorageDirectory();
    if (directory == null) {
      throw StateError('此裝置無法取得 App 專用外部儲存空間');
    }

    final mapsDirectory = Directory(
      [directory.path, directoryName].join(Platform.pathSeparator),
    );
    await mapsDirectory.create(recursive: true);

    final path = [mapsDirectory.path, fileName].join(Platform.pathSeparator);
    if (!await File(path).exists()) {
      throw StateError('找不到離線瓦片包：$path');
    }

    final database = await openDatabase(
      path,
      readOnly: true,
      singleInstance: false,
    );
    try {
      final metadata = await database.query(
        'metadata',
        columns: const ['value'],
        where: 'name = ?',
        whereArgs: const ['format'],
        limit: 1,
      );
      if (metadata.isEmpty || metadata.single['value'] != 'jpg') {
        throw FormatException('離線瓦片包格式無效：預期 MBTiles JPEG');
      }
      return TaiwanMbtilesSource._(database);
    } catch (_) {
      await database.close();
      rethrow;
    }
  }

  Future<Uint8List?> getTile(int zoom, int column, int row) {
    final key = '$zoom:$column:$row';
    final cached = _tileCache.remove(key);
    if (cached != null) {
      _tileCache[key] = cached;
      return Future.value(cached);
    }
    return _pendingTiles.putIfAbsent(key, () async {
      try {
        final tmsRow = (1 << zoom) - 1 - row;
        final records = await _database.query(
          'tiles',
          columns: const ['tile_data'],
          where: 'zoom_level = ? AND tile_column = ? AND tile_row = ?',
          whereArgs: [zoom, column, tmsRow],
          limit: 1,
        );
        if (records.isEmpty) return null;

        final data = records.single['tile_data'];
        if (data is Uint8List) {
          _cacheTile(key, data);
          return data;
        }
        if (data is List<int>) {
          final bytes = Uint8List.fromList(data);
          _cacheTile(key, bytes);
          return bytes;
        }
        throw FormatException('瓦片資料不是二進位 JPEG：$key');
      } finally {
        _pendingTiles.remove(key);
      }
    });
  }

  void _cacheTile(String key, Uint8List data) {
    _tileCache[key] = data;
    if (_tileCache.length > maxCachedTiles) {
      _tileCache.remove(_tileCache.keys.first);
    }
  }

  Future<void> close() => _database.close();
}
