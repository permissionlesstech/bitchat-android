import 'dart:async';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/services.dart';

/// 聊天 method 名稱，對應 Kotlin `ChatBridge` 的 companion 常數（命名規則 `chat_<動詞><受詞>`）。
abstract final class ChatMethods {
  static const sendMessage = 'chat_sendMessage';
  static const setNickname = 'chat_setNickname';
  static const getNickname = 'chat_getNickname';
  static const requestSnapshot = 'chat_requestSnapshot';
  static const updateInput = 'chat_updateInput';
  static const selectCommandSuggestion = 'chat_selectCommandSuggestion';
  static const selectMentionSuggestion = 'chat_selectMentionSuggestion';
  static const clearSuggestions = 'chat_clearSuggestions';
  static const startPrivateChat = 'chat_startPrivateChat';
  static const endPrivateChat = 'chat_endPrivateChat';
  static const openLatestUnreadPrivateChat = 'chat_openLatestUnreadPrivateChat';
  static const takePendingNavigation = 'chat_takePendingNavigation';
  static const toggleFavorite = 'chat_toggleFavorite';
}

/// 聊天 method 拒絕時的錯誤碼（[PlatformException.code]），對應 Kotlin `ChatBridge` 的 `ERROR_*`
/// 常數。原生沒有做任何事；畫面依錯誤碼告訴使用者原因。
abstract final class ChatErrors {
  /// [ChatMethods.sendMessage] 收到頻道指令（`/j`、`/join`）：頻道功能尚未支援（#49 P3），
  /// 原生沒有送出、也沒有加入頻道。
  static const channelsUnsupported = 'CHANNELS_UNSUPPORTED';

  /// [ChatMethods.sendMessage] 的 `privateChat` 不是原生目前選定的對話（同一個人的不同 ID 算同一個
  /// 對話）：原生沒有送出。Dart 的選定私訊隨下一份快照跟上，再送一次即可。
  static const privateChatChanged = 'PRIVATE_CHAT_CHANGED';
}

/// `sendHealthReport` 沒有送出時原生回的錯誤碼（[PlatformException.code]），對應 Kotlin
/// `BitchatFlutterChannels` 的 `"sendHealthReport"` 分支。
abstract final class HealthReportErrors {
  /// 原生認不得這份 Broadcast Tier（例如 handle 或 Status 不合法）。
  static const invalidFormat = 'INVALID_FORMAT';

  /// mesh 服務沒有在跑。
  static const serviceNotReady = 'SERVICE_NOT_READY';

  /// 編碼或交給 mesh 時出錯。
  static const sendFailed = 'SEND_FAILED';
}

/// 實機量測工具（#70）的 method 名稱，對應 Kotlin `ExperimentBridge`（只在 debug build，
/// `app/src/debug/.../experiment/ExperimentBridge.kt`）的 companion 常數（命名規則
/// `experiment_<動詞><受詞>`）。release build 沒有這些 method，只有 debug 限定的實驗畫面會呼叫。
abstract final class ExperimentMethods {
  static const getStatus = 'experiment_getStatus';
  static const startSender = 'experiment_startSender';
  static const stopSender = 'experiment_stopSender';
}

/// 實驗 method 拒絕時的錯誤碼（[PlatformException.code]），對應 Kotlin `ExperimentBridge` 的
/// `ERROR_*` 常數。原生沒有做任何事；實驗畫面依錯誤碼告訴操作者原因。
abstract final class ExperimentErrors {
  /// 參數不合法（裝置編號、筆數、間隔、TTL、開始時間或 Status）。
  static const invalidArgument = 'INVALID_ARGUMENT';

  /// mesh 前景服務沒有在跑。
  static const serviceNotReady = 'SERVICE_NOT_READY';

  /// [ExperimentMethods.startSender]：發送器已在等待開始或發送中，要先停止。
  static const alreadyRunning = 'ALREADY_RUNNING';
}

/// 設定頁 method 名稱，對應 Kotlin `SettingsBridge` 的 companion 常數（命名規則 `settings_<動詞><受詞>`，#80）。
abstract final class MapMethods {
  static const clearDataCache = 'clearMapDataCache';
}

abstract final class SettingsMethods {
  static const getState = 'settings_getState';
  static const requestPermission = 'settings_requestPermission';
  static const openAppSettings = 'settings_openAppSettings';
  static const requestEnableBluetooth = 'settings_requestEnableBluetooth';
  static const requestIgnoreBatteryOptimization =
      'settings_requestIgnoreBatteryOptimization';
  static const setBackgroundEnabled = 'settings_setBackgroundEnabled';
  static const setAutoStartEnabled = 'settings_setAutoStartEnabled';
  static const setNotificationAutoRestore =
      'settings_setNotificationAutoRestore';
  static const panicClear = 'settings_panicClear';
}

/// 設定頁 method 拒絕時的錯誤碼（[PlatformException.code]），對應 Kotlin `SettingsBridge` 的 `ERROR_*`。
abstract final class SettingsErrors {
  /// 沒有 Activity 可以開系統的詢問或設定畫面。
  static const noActivity = 'NO_ACTIVITY';

  /// 這個 Android 版本不會要求這類權限。
  static const unavailable = 'UNAVAILABLE';

  /// 要先允許「附近的裝置」，系統才肯詢問開啟藍牙。
  static const permissionRequired = 'PERMISSION_REQUIRED';

  /// 已經有一次緊急清除在進行。
  static const panicInProgress = 'PANIC_IN_PROGRESS';

  /// 緊急清除跑完了，但無法確認聊天紀錄已刪除；原生讓 mesh 維持停止。
  static const panicIncomplete = 'PANIC_INCOMPLETE';

  /// 緊急清除途中出錯。
  static const panicFailed = 'PANIC_FAILED';
}

/// 聊天快照事件的 `type`，對應 Kotlin `ChatSerialization` 的常數（命名規則 `chat_<snake_case>`）。
abstract final class ChatEvents {
  /// `{type, messages: List<Map>}`：公開 mesh 時間線的完整快照，依時間線順序。
  static const publicMessages = 'chat_public_messages';

  /// `{type, nickname: String}`：自己的 mesh 暱稱（原生 `ChatViewModel.nickname`），原樣、可能是空字串。
  static const nickname = 'chat_nickname';

  /// `{type, onlineCount: int, peopleCount: int, peers: List<Map>}`：線上人數與 mesh peer 列表的完整快照，
  /// 依原生列表的顯示順序：在線 peer，再接離線的我的最愛（`connection: offline`，#58）；每列帶
  /// 我的最愛星號的兩個方向 `isFavorite`、`theyFavoritedUs`（見 `models/chat_peer.dart`）。已有對話的
  /// peer 列在 [conversations]，不在這裡；`peopleCount` 不算他們，`onlineCount`（標頭的人數）照算（#73）。
  static const peers = 'chat_peers';

  /// `{type, showCommands: bool, commands: List<Map>, showMentions: bool, mentions: List<String>}`：
  /// 輸入框 `/` 指令與 `@` 提及補完的完整快照（原生 `ChatViewModel` 的補完狀態，見
  /// `models/chat_suggestions.dart`）。
  static const suggestions = 'chat_suggestions';

  /// `{type, peerID: String?, conversationID: String?, displayName: String?, draft: String?,
  /// isFavorite: bool?, theyFavoritedUs: bool?}`：原生「目前選定的私訊對象」
  /// （`ChatViewModel.selectedPrivateChatPeer`）與它標頭的我的最愛星號（#58）；沒有時除 `type` 外
  /// 都是 null。私訊畫面依它開關（見 `models/private_chat.dart`）。
  static const selectedPrivatePeer = 'chat_selected_private_peer';

  /// `{type, chats: {conversationID: List<Map>}}`：原生持有的所有私訊對話，訊息 map 與
  /// [publicMessages] 相同（見 `models/chat_message.dart`）。自己送出的私訊帶 `deliveryStatus`，
  /// 送達、已讀或失敗時隨快照更新。被 `/block` 的對象傳來的訊息不在裡面（[publicMessages] 也是，#58）。
  static const privateChats = 'chat_private_chats';

  /// `{type, hasUnread: bool, conversations: {conversationID: int}}`：原生是否有未讀私訊（原生標頭的
  /// 未讀信封），以及每個有未讀的對話的未讀數（見 `models/chat_unread.dart`）。在線 peer 的未讀數也
  /// 在 [peers] 的列上。
  static const unread = 'chat_unread';

  /// `{type, navigation: Map?}`：使用者點了聊天通知、Dart 還沒處理的目的地（#57），沒有時
  /// `navigation` 為 null（見 `models/chat_navigation.dart`）。它只是提醒：要導航前一定先以
  /// [ChatMethods.takePendingNavigation] 取走，照取到的去，每個點擊只處理一次。
  static const pendingNavigation = 'chat_pending_navigation';

  /// `{type, state: loading|ready|error, conversations: List<Map>}`：原生持有的所有私訊對話（#73），
  /// 對方在線與離線在同一份清單、依原生順序；每列帶名稱、最後一則預覽與時間、未讀數、是否在線
  /// （見 `models/chat_conversation.dart`）。peer 列表上方的「對話」區段就是它，點一列以
  /// `conversationID` 開啟私訊（[ChatMethods.startPrivateChat]）。
  static const conversations = 'chat_conversations';
}

class BitchatBridge {
  static const MethodChannel _method = MethodChannel(
    'com.bitchat/bridge/methods',
  );
  static const EventChannel _events = EventChannel('com.bitchat/bridge/events');

  // Cached broadcast stream — receiveBroadcastStream() must only be called once
  // per EventChannel; a second call creates a conflicting platform listener.
  static Stream<Map<String, dynamic>>? _cachedEventStream;

  static Stream<Map<String, dynamic>> events() {
    return _cachedEventStream ??= _events.receiveBroadcastStream().map((
      dynamic e,
    ) {
      if (e is Map) {
        return e.map((k, v) => MapEntry(k.toString(), v));
      }
      return <String, dynamic>{'type': 'unknown', 'raw': e};
    }).asBroadcastStream();
  }

  /// 設定流程用的舊方法（[getSystemStatus]、[checkPermissions]、[requestPermissions]、[isRegistered]、
  /// [startMesh]）共用的錯誤處理（#71）。
  ///
  /// 只有 [MissingPluginException] 會降級成 [fallback]：這個平台沒有原生實作（iOS、測試、engine 尚未
  /// 接上）。iOS 的 setup 流程依賴這個降級，例如 [isRegistered] 回 false 才會往登入走，所以不能拿掉，
  /// 但會留下 log，分得出是「沒有原生」而不是原生真的這樣回答。
  ///
  /// 原生以 `result.error(...)` 回報的 [PlatformException]（例如 `START_FAILED`、`NO_ACTIVITY`）照常
  /// 往上拋，連同錯誤碼與原因，由呼叫端決定怎麼告訴使用者（與 [setNickname] 等方法一致）。
  static Future<T> _degradeWithoutNative<T>(
    String method,
    T fallback,
    Future<T> Function() call,
  ) async {
    try {
      return await call();
    } on MissingPluginException {
      debugPrint(
        'BitchatBridge: $method has no native implementation on this platform; using $fallback',
      );
      return fallback;
    }
  }

  /// 獲取當前系統狀態 (藍牙、位置、權限)。沒有原生實作時回傳 null；原生錯誤（[PlatformException]）
  /// 往上拋。

  static Future<Map<String, dynamic>?> getSystemStatus() =>
      _degradeWithoutNative<Map<String, dynamic>?>(
        'getSystemStatus',
        null,
        () async {
          final Map<dynamic, dynamic>? result = await _method.invokeMethod<Map>(
            'getSystemStatus',
          );
          return result?.map((k, v) => MapEntry(k.toString(), v));
        },
      );

  /// 從裝置相簿挑一張照片當大頭貼。回傳已在原生端裁成正方形、縮成小圖的 JPEG；
  /// 使用者取消時為 null。沒有原生實作的平台（例如 iOS 開發用模擬機）會丟
  /// [MissingPluginException]，呼叫端要自行處理。
  static Future<Uint8List?> pickAvatarPhoto() =>
      _method.invokeMethod<Uint8List>('pickAvatarPhoto');

  /// 檢查權限是否已開啟 (通知、藍牙、位置)。沒有原生實作時回傳 false；原生錯誤（[PlatformException]）
  /// 往上拋，不當成「沒有權限」。
  static Future<bool> checkPermissions() => _degradeWithoutNative(
    'checkPermissions',
    false,
    () async {
      final bool? result = await _method.invokeMethod<bool>('checkPermissions');
      return result ?? false;
    },
  );

  /// 請原生跳出權限請求。回傳是否已發出請求（不是使用者是否允許）；沒有原生實作時回傳 false。
  /// 原生沒有發出請求時（例如 `NO_ACTIVITY`）以 [PlatformException] 往上拋。
  static Future<bool> requestPermissions() =>
      _degradeWithoutNative('requestPermissions', false, () async {
        final bool? result = await _method.invokeMethod<bool>(
          'requestPermissions',
        );
        return result ?? false;
      });

  /// 檢查原生端是否已有身分資料（已註冊）。沒有原生實作時回傳 false（iOS 的 setup 流程依賴它）；
  /// 原生讀不到身分資料時（例如 `IDENTITY_UNAVAILABLE`）以 [PlatformException] 往上拋，不當成「尚未註冊」。
  static Future<bool> isRegistered() =>
      _degradeWithoutNative('isRegistered', false, () async {
        final bool? result = await _method.invokeMethod<bool>('isRegistered');
        return result ?? false;
      });

  /// 啟動 Mesh 服務。回傳 true 表示已啟動；沒有原生實作時回傳 false。原生啟動失敗時（例如
  /// `START_FAILED`）以 [PlatformException] 往上拋，[PlatformException.message] 是 Kotlin 回報的原因。
  static Future<bool> startMesh() =>
      _degradeWithoutNative('startMesh', false, () async {
        final bool? result = await _method.invokeMethod<bool>('startMesh');
        return result ?? false;
      });

  /// 把使用者輸入的文字交給原生聊天核心（`ChatViewModel.sendMessage`）。
  ///
  /// 送往哪裡（公開 mesh、目前開啟的私訊、`/` 指令）由原生核心依它自己的「目前選定的私訊對象」
  /// 決定，Dart 端沒有 peerId／isPublic 之類指定收件者的參數（#9）。
  ///
  /// [privateChat] 只標明文字是在哪個輸入框打的：公開聊天室為 null，私訊畫面為
  /// [ChatEvents.selectedPrivatePeer] 的 `peerID`。原生端只在它的選定對話與此相同（同一個人的
  /// mesh peer ID、Noise 公鑰、`contact_…` 算同一個）時才送出，否則以錯誤碼
  /// [ChatErrors.privateChatChanged] 拒絕（沒送出），避免公開訊息被送成私訊、或私訊被公開廣播。
  /// 頻道指令（`/j`、`/join`）不交給原生核心，以錯誤碼 [ChatErrors.channelsUnsupported] 拒絕。
  /// 空白文字不會送出。回傳原生核心是否接受（false 是原生核心自己拒絕，例如對方已被封鎖）；
  /// bridge 錯誤（例如 [PlatformException]）會往上拋。
  static Future<bool> sendMessage(String text, {String? privateChat}) async {
    final bool? accepted = await _method.invokeMethod<bool>(
      ChatMethods.sendMessage,
      <String, dynamic>{'text': text, 'privateChat': privateChat},
    );
    return accepted ?? false;
  }

  /// 開啟私訊（原生 `ChatViewModel.startPrivateChat`：載入儲存的紀錄、設為選定對象、清未讀、
  /// 送已讀回條、必要時開始 Noise 交握）。[peerID] 是 peer 列表的 peer ID 或對話 ID；原生可能改用
  /// 正規化後的對話 ID，也可能拒絕（例如已封鎖）。完成後回傳當下的 [ChatEvents.selectedPrivatePeer]
  /// map。bridge 錯誤會往上拋。
  static Future<Map<dynamic, dynamic>?> startPrivateChat(String peerID) =>
      _method.invokeMethod<Map>(ChatMethods.startPrivateChat, <String, dynamic>{
        'peerID': peerID,
      });

  /// 結束私訊焦點（原生 `ChatViewModel.endPrivateChat`），之後輸入的文字回到公開聊天室。
  /// 回傳當下的 [ChatEvents.selectedPrivatePeer] map（`peerID` 為 null）。bridge 錯誤會往上拋。
  static Future<Map<dynamic, dynamic>?> endPrivateChat() =>
      _method.invokeMethod<Map>(ChatMethods.endPrivateChat);

  /// 原生標頭未讀信封的動作（原生 `ChatViewModel.openLatestUnreadPrivateChat`）：原生挑出最新收到
  /// 未讀私訊的對話，回傳要開啟的對話 ID；沒有未讀時回傳 null。只挑、不開啟：拿到 ID 後照一般流程
  /// 開私訊畫面（[startPrivateChat]）。bridge 錯誤會往上拋。
  static Future<String?> openLatestUnreadPrivateChat() =>
      _method.invokeMethod<String>(ChatMethods.openLatestUnreadPrivateChat);

  /// 取走使用者點聊天通知要去的地方（#57）：`{target: privateChat, peerID, senderNickname}` 或
  /// `{target: publicChat}`；沒有（或已被取走）時回傳 null。取走後原生不再持有它。只取、不開啟：
  /// 私訊畫面照一般流程呼叫 [startPrivateChat]。bridge 錯誤會往上拋。
  static Future<Map<dynamic, dynamic>?> takePendingChatNavigation() =>
      _method.invokeMethod<Map>(ChatMethods.takePendingNavigation);

  /// 切換我的最愛（原生 `ChatViewModel.toggleFavorite`，原生私訊標頭星號做的事）：加入或移出，
  /// 記下對方的 Noise 公鑰與暱稱（離線後仍找得到），對方在 mesh 上時通知他。[peerID] 原樣傳過去：
  /// peer 列表那一列的 `peerID`（在線是 mesh peer ID，離線的最愛是 Noise 公鑰），或私訊畫面的
  /// [ChatEvents.selectedPrivatePeer] `peerID`。新的星號經 [ChatEvents.peers]、
  /// [ChatEvents.selectedPrivatePeer] 快照回推。bridge 錯誤會往上拋。
  static Future<void> toggleFavorite(String peerID) async {
    await _method.invokeMethod<void>(
      ChatMethods.toggleFavorite,
      <String, dynamic>{'peerID': peerID},
    );
  }

  /// 設定 mesh 暱稱（原生 `ChatViewModel.setNickname`：儲存後立即重新 announce）。
  ///
  /// 暱稱會隨 announce 明文廣播給範圍內所有裝置，只能傳使用者親自為 mesh 輸入的名稱，
  /// 絕不能傳帳號的真實姓名（`AppUser.name`，ADR-0003）。文字原樣傳過去：空白與長度的
  /// 處理沿用上游，這裡不另訂規則。新值經 [ChatEvents.nickname] 快照回推；
  /// bridge 錯誤（例如 [MissingPluginException]、[PlatformException]）會往上拋。
  static Future<void> setNickname(String nickname) async {
    await _method.invokeMethod<void>(ChatMethods.setNickname, <String, dynamic>{
      'nickname': nickname,
    });
  }

  /// 一次性讀取目前的 mesh 暱稱。畫面請改看 `ChatService.nickname`（[ChatEvents.nickname] 快照），
  /// 它會跟著任何來源的變更更新。bridge 錯誤會往上拋。
  static Future<String> getNickname() async {
    final String? nickname = await _method.invokeMethod<String>(
      ChatMethods.getNickname,
    );
    if (nickname == null) {
      throw PlatformException(
        code: 'NO_NICKNAME',
        message: '${ChatMethods.getNickname} answered null',
      );
    }
    return nickname;
  }

  /// 請原生端立刻重推所有 `chat_*` 快照事件（經事件串流送達，不是回傳值）。
  ///
  /// 事件串流是共用的 broadcast stream，原生端只在「第一個」Dart listener 訂閱時推快照；
  /// 較晚訂閱的 `ChatService` 用這個補拿目前狀態。
  static Future<void> requestChatSnapshot() async {
    await _method.invokeMethod<void>(ChatMethods.requestSnapshot);
  }

  /// 輸入框文字改變時呼叫（原生輸入框在每次文字變化時做的事）。[privateChat] 同 [sendMessage]：
  /// 公開聊天室（null）的文字用來更新 `/` 指令與 `@` 提及補完，結果經 [ChatEvents.suggestions]
  /// 快照回推；私訊輸入框的文字只存成該私訊的草稿（原生私訊畫面沒有補完）。文字原樣傳過去。
  /// bridge 錯誤會往上拋。
  static Future<void> updateChatInput(
    String text, {
    String? privateChat,
  }) async {
    await _method.invokeMethod<void>(ChatMethods.updateInput, <String, dynamic>{
      'text': text,
      'privateChat': privateChat,
    });
  }

  /// 選取一個 `/` 指令補完，回傳輸入框的新文字（原生 `selectCommandSuggestion`，同時關閉清單）。
  ///
  /// 只傳指令名稱，原生端從它目前提供的清單找回上游物件；該指令已不在清單上時回傳 null，
  /// 輸入框不要變。bridge 錯誤會往上拋。
  static Future<String?> selectCommandSuggestion(String command) =>
      _method.invokeMethod<String>(
        ChatMethods.selectCommandSuggestion,
        <String, dynamic>{'command': command},
      );

  /// 選取一個 `@` 提及補完，回傳輸入框的新文字（原生 `selectMentionSuggestion`：把正在輸入的
  /// `@片段` 換成 `@暱稱 `，同時關閉清單）。[currentText] 是選取當下輸入框的文字。
  /// bridge 錯誤會往上拋。
  static Future<String> selectMentionSuggestion(
    String nickname,
    String currentText,
  ) async {
    final String? text = await _method.invokeMethod<String>(
      ChatMethods.selectMentionSuggestion,
      <String, dynamic>{'nickname': nickname, 'currentText': currentText},
    );
    if (text == null) {
      throw PlatformException(
        code: 'NO_TEXT',
        message: '${ChatMethods.selectMentionSuggestion} answered null',
      );
    }
    return text;
  }

  /// 關閉兩個補完清單（原生輸入框在送出、清空文字後這樣做）。bridge 錯誤會往上拋。
  static Future<void> clearMapDataCache() async {
    await _method.invokeMethod<void>(MapMethods.clearDataCache);
  }

  static Future<void> clearChatSuggestions() async {
    await _method.invokeMethod<void>(ChatMethods.clearSuggestions);
  }

  /// 發送 Health Report 的 Broadcast Tier（BLE 廣播 + 網路回報）。
  ///
  /// 傳入的 map 只應含不具識別性的欄位：`reporterHandle`、`status`（中文 label）、
  /// 以及原始 `lat`/`lng`（由原生端就地降精度為 geohash）。姓名、電話、血型、自由文字
  /// 等 Detail Tier 欄位不要放進來——原生端也會忽略（見 ADR-0003）。
  ///
  /// 正常完成才表示原生已把它交給 mesh 廣播。沒有送出時往上拋，呼叫端必須讓使用者知道附近的人
  /// 收不到：[PlatformException] 是原生回報的原因（錯誤碼見 [HealthReportErrors]）；
  /// [MissingPluginException] 是這個平台沒有原生 mesh（例如 iOS、測試）。
  static Future<void> sendHealthReport(Map<String, dynamic> broadcastTier) =>
      _method.invokeMethod<void>('sendHealthReport', broadcastTier);

  /// 實驗畫面（#70，debug 限定）讀取現場即時狀態，回傳：
  ///
  /// - `links`（int）：目前直連數。
  /// - `powerMode`（String）：`PowerManager.PowerMode`（`PERFORMANCE`、`BALANCED`、`POWER_SAVER`、
  ///   `ULTRA_LOW_POWER`）。這是 app 自己的模式，不受系統省電模式影響。
  /// - `systemPowerSave`（bool?）：Android 系統省電模式是否開著。
  /// - `peerId`（String?）：本機 mesh peerID 前 8 碼；mesh 沒在跑時為 null。
  /// - `rx20s`、`rx60s`（`Map<String, int>`）：最近 20 s／60 s 內依實驗 handle 統計的 `RX` 筆數，
  ///   只列至少 1 筆的 handle。
  /// - `sender`：發送器狀態，格式同 [startExperimentSender] 的回傳值。
  ///
  /// 解析見 `models/experiment_status.dart`。bridge 錯誤會往上拋。
  static Future<Map<dynamic, dynamic>?> getExperimentStatus() =>
      _method.invokeMethod<Map>(ExperimentMethods.getStatus);

  /// 啟動實驗用自動發送器（#70，debug 限定）：裝置 [device]（1–10）以固定實驗 handle
  /// （`ee` 加裝置編號補零成 10 位）送出 [count] 筆（至少 1）Health Report Broadcast Tier，每筆間隔
  /// [intervalMs]（0 為突發），TTL 為 [ttl]（0～7），Status 為 [status]（「安全」「輕傷」「重傷」）。
  /// 只走 mesh，不寫入 Firestore。發送在原生的 mesh 前景服務裡跑，畫面關掉、app 進背景仍會繼續。
  /// [keepAwake] 為 true 時原生在 run 期間持有 wake lock，螢幕關閉也照排程送；E4 要傳 false，
  /// 否則手機無法休眠，背景存活與耗電會量得偏樂觀。
  ///
  /// [startAt] 是本機牆鐘 `HH:mm:ss`：原生取它的下一次出現（今天還沒到就今天，否則明天），讓多支
  /// 手機同時開始；null 表示立即開始。實際開始時間在回傳的 `startsAtMs`。
  ///
  /// 回傳發送器狀態 `{state: idle|waiting|sending|done|stopped, sent, failed, written, noLink, total, startsAtMs?,
  /// handle?, ttl?, intervalMs?, keepAwake?}`。沒有啟動時以 [PlatformException] 往上拋，錯誤碼見
  /// [ExperimentErrors]。
  static Future<Map<dynamic, dynamic>?> startExperimentSender({
    required int device,
    required int count,
    required int intervalMs,
    required int ttl,
    required String? startAt,
    required String status,
    required bool keepAwake,
  }) => _method
      .invokeMethod<Map>(ExperimentMethods.startSender, <String, dynamic>{
        'device': device,
        'count': count,
        'intervalMs': intervalMs,
        'ttl': ttl,
        'startAt': startAt,
        'status': status,
        'keepAwake': keepAwake,
      });

  /// 停止實驗發送器（等待開始或發送中都可停；沒在跑時無害），回傳停止後的發送器狀態，格式同
  /// [startExperimentSender]。bridge 錯誤會往上拋。
  static Future<Map<dynamic, dynamic>?> stopExperimentSender() =>
      _method.invokeMethod<Map>(ExperimentMethods.stopSender);

  // ── 設定頁（#80）：以下 bridge 錯誤（[PlatformException]、[MissingPluginException]）一律往上拋，
  // 錯誤碼見 [SettingsErrors]。

  /// 一次取得權限、藍牙、電池最佳化、mesh 背景運作的三個開關與版本號（見 `models/settings_state.dart`）。
  static Future<Map<String, dynamic>> getSettingsState() async {
    final Map<dynamic, dynamic>? state = await _method.invokeMethod<Map>(
      SettingsMethods.getState,
    );
    if (state == null) {
      throw PlatformException(
        code: 'NO_STATE',
        message: '${SettingsMethods.getState} answered null',
      );
    }
    return state.map((k, v) => MapEntry(k.toString(), v));
  }

  /// 處理某一類權限（`SettingsPermissionCategory.wireName`）：還沒允許時跳出系統的詢問，已允許或
  /// 系統不再詢問時開啟 App 資訊頁讓使用者自己切換，電池最佳化則請系統排除本 App。回傳原生做了哪一個
  /// （`prompted`、`openedAppSettings`、`requestedBatteryExemption`）。
  static Future<String?> requestSettingsPermission(String category) =>
      _method.invokeMethod<String>(
        SettingsMethods.requestPermission,
        <String, dynamic>{'category': category},
      );

  /// 開啟系統設定裡本 App 的資訊頁。
  static Future<void> openAppSettings() =>
      _method.invokeMethod<void>(SettingsMethods.openAppSettings);

  /// 請系統詢問使用者是否開啟藍牙（App 不能直接開關系統藍牙）。
  static Future<void> requestEnableBluetooth() =>
      _method.invokeMethod<void>(SettingsMethods.requestEnableBluetooth);

  /// 請系統詢問是否讓本 App 不受電池最佳化限制。
  static Future<void> requestIgnoreBatteryOptimization() => _method
      .invokeMethod<void>(SettingsMethods.requestIgnoreBatteryOptimization);

  /// 背景執行；關閉時原生會停止前景服務。
  static Future<void> setBackgroundEnabled(bool enabled) =>
      _method.invokeMethod<void>(
        SettingsMethods.setBackgroundEnabled,
        <String, dynamic>{'enabled': enabled},
      );

  /// 開機自動啟動。
  static Future<void> setAutoStartEnabled(bool enabled) =>
      _method.invokeMethod<void>(
        SettingsMethods.setAutoStartEnabled,
        <String, dynamic>{'enabled': enabled},
      );

  /// 常駐通知被滑掉時立即恢復；原生會立刻重發一次通知讓設定生效。
  static Future<void> setNotificationAutoRestore(bool enabled) =>
      _method.invokeMethod<void>(
        SettingsMethods.setNotificationAutoRestore,
        <String, dynamic>{'enabled': enabled},
      );

  /// 原生 `ChatViewModel.panicClearAllData()`：清除聊天紀錄、身分金鑰、媒體檔與通知，換一組新身分。
  /// 原生清除完才回來；沒有清乾淨時以 [SettingsErrors] 的錯誤碼往上拋。
  static Future<void> panicClear() =>
      _method.invokeMethod<void>(SettingsMethods.panicClear);
}
