package com.bitchat.android.flutter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.InboundPacketBridge
import com.bitchat.android.service.HealthReportBroadcast
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import com.google.gson.Gson
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 核心橋樑：負責 Kotlin 原生功能與 Flutter UI 的通訊（系統狀態、權限、Health Report）。
 *
 * 同一個 BinaryMessenger 上，每個 channel 名稱只能有一個 handler（後設定的會覆蓋前者），
 * 所以兩個 channel 的 handler 只由本類別註冊：
 * - method：[BridgeMethodDispatcher] 先交給本類別，不認得的再依序交給 [additionalMethodHandlers]
 *   （例如 [ChatBridge]），都不認得才回 `notImplemented`。
 * - event：共用的 [events]（[BridgeEventEmitter]），各元件以 `type` 區分事件。
 *
 * 生命週期與一個 Flutter engine 相同，engine 清理時必須呼叫 [destroy]。
 */
class BitchatFlutterChannels(
    private val context: Context,
    messenger: BinaryMessenger,
    private val activity: Activity? = null,
    private val events: BridgeEventEmitter = BridgeEventEmitter(),
    additionalMethodHandlers: List<BridgeMethodHandler> = emptyList(),
    /** `pickAvatarPhoto` 的實作；沒有 Activity（例如背景 engine）時為 null。 */
    private val avatarPhotoPicker: AvatarPhotoPicker? = null,
    /** 回覆 method 的執行緒（主執行緒）；[destroy] 時取消。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** 讀加密身分儲存區（keystore 與磁碟）的地方，不在主執行緒。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BridgeMethodHandler {

    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME)

    // 開啟 EncryptedSharedPreferences 要建 MasterKey、解開 keyset（keystore 與磁碟），所以不在 engine
    // 建立時於主執行緒開，而是第一次用到時在 [ioDispatcher] 上開。
    private val identityManager by lazy { SecureIdentityStateManager(context) }
    private val permissionManager = PermissionManager(context)
    private val gson = Gson()

    // 監聽系統藍牙與位置狀態變更
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            emitStatusUpdate()
        }
    }

    // 監聽來自 Mesh 的封包，統一格式轉發給 Flutter 自行解析
    // 注意：ContentTag 已經在 MessageHandler.handleTaggedBroadcast() 那一層被去除，
    // 這裡拿到的 packet.payload 一定是「未加 tag」的原始資料，不可再嘗試偵測/剝除 tag byte
    // （payload[0] 剛好等於 0x01 是合法資料，例如 reporterId 長度恰為 1 時，會被誤判成 tag 而遭到錯誤剝除）。
    // 每個實例註冊自己的 listener，destroy() 只移除自己的，不會清掉重建後新實例的 listener。
    private val packetListener: (BitchatPacket) -> Unit = { packet ->
        Log.d("BitchatBridge", "📨 收到封包，類型: 0x${packet.type.toString(16).uppercase()}, 大小: ${packet.payload.size}")
        emitEvent(mapOf(
            "type"       to "packet",
            "packetType" to packet.type.toInt(),
            "senderId"   to packet.senderID.toHexString(),
            "timestamp"  to packet.timestamp.toLong(),
            "payload"    to packet.payload.map { it.toInt() and 0xFF }
        ))
    }

    init {
        methodChannel.setMethodCallHandler(
            BridgeMethodDispatcher(listOf(this) + additionalMethodHandlers)
        )
        eventChannel.setStreamHandler(events)
        // Flutter 開始監聽時先推一次目前的系統狀態
        events.addOnListenCallback(::emitStatusUpdate)

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        context.registerReceiver(statusReceiver, filter)

        InboundPacketBridge.addListener(packetListener)
    }

    private fun getStatusMap(): Map<String, Any> {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothEnabled = bluetoothAdapter?.isEnabled ?: false
        
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                             locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        
        val permissionsGranted = permissionManager.areRequiredPermissionsGranted()
        val notificationGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissionManager.isPermissionGranted(android.Manifest.permission.POST_NOTIFICATIONS)
        } else true

        return mapOf(
            "type" to "system_status",
            "bluetoothEnabled" to bluetoothEnabled,
            "locationEnabled" to locationEnabled,
            "permissionsGranted" to permissionsGranted,
            "notificationGranted" to notificationGranted
        )
    }

    private fun emitStatusUpdate() {
        emitEvent(getStatusMap())
    }

    /** 處理系統狀態、權限與 Health Report 相關 method；不認得的回 false 交給下一個 handler。 */
    override fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        when (call.method) {
            "clearMapDataCache" -> {
                try {
                    val staleFiles = listOf(
                        "minsk-pass.mwm",
                        "Taiwan_North.mwm",
                        "Taiwan_South.mwm",
                        ".comaps_data_extracted"
                    )
                    staleFiles.forEach { name -> context.filesDir.resolve(name).deleteRecursively() }
                    context.filesDir.resolve("borders").deleteRecursively()
                    context.filesDir.resolve("test_data").deleteRecursively()
                    result.success(true)
                } catch (e: Exception) {
                    result.error("MAP_CACHE_CLEAR_FAILED", e.message, null)
                }
            }

            "getSystemStatus" -> {
                result.success(getStatusMap())
            }

            "checkPermissions" -> {
                // Only Bluetooth and location gate the setup screen. POST_NOTIFICATIONS is optional
                // (PermissionManager.getRequiredPermissions, MeshForegroundService): once the user
                // turns it off in the system settings the system never prompts for it again, so
                // gating on it kept the setup screen re-requesting forever.
                result.success(permissionManager.areRequiredPermissionsGranted())
            }

            "requestPermissions" -> {
                if (activity == null) {
                    result.error("NO_ACTIVITY", "Cannot request permissions without an activity", null)
                    return true
                }
                // Includes the Wi‑Fi Aware permissions when that transport is on and supported;
                // checkPermissions / areRequiredPermissionsGranted still only gate on the
                // critical set, so declining Wi‑Fi Aware leaves the setup screen passable.
                val permissions = mutableListOf<String>()
                permissions.addAll(permissionManager.getPermissionsToRequest())
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                // 設定頁（#80）靠這筆紀錄分辨「從沒問過」與「已被永久拒絕」。
                permissionManager.markPermissionsRequested(permissions)
                activity.requestPermissions(permissions.toTypedArray(), 1001)
                result.success(true)
            }

            "isRegistered" -> answerIsRegistered(result)

            "startMesh" -> {
                try {
                    MeshForegroundService.start(context)
                    val service = MeshServiceHolder.getOrCreate(context)
                    service.startServices()
                    result.success(true)
                } catch (e: Exception) {
                    result.error("START_FAILED", e.message, null)
                }
            }

            "pickAvatarPhoto" -> {
                val picker = avatarPhotoPicker
                if (picker == null) {
                    result.error("NO_ACTIVITY", "Cannot open the photo picker without an activity", null)
                } else {
                    picker.pick(result)
                }
            }

            "sendHealthReport" -> {
                Log.d("BitchatBridge", "🟢 sendHealthReport 被調用，參數類型: ${call.arguments?.javaClass?.simpleName}")
                try {
                    // 期望接收二進制數據
                    val payload = when (call.arguments) {
                        is List<*> -> {
                            // 如果 Flutter 發送的是 List<int>（二進制）
                            (call.arguments as List<*>).filterIsInstance<Int>().map { it.toByte() }.toByteArray()
                        }
                        is Map<*, *> -> {
                            // 如果仍然是 Map（JSON），則轉換為 HealthReportPayload 進行二進制編碼
                            val reportMap = call.arguments as Map<*, *>
                            val report = convertMapToHealthReportPayload(reportMap)
                            if (report != null) {
                                report.encode()
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                    
                    if (payload == null) {
                        Log.e("BitchatBridge", "❌ payload 為 null，無法編碼")
                        result.error("INVALID_FORMAT", "Unsupported payload format", null)
                        return true
                    }
                    Log.d("BitchatBridge", "✅ payload 編碼成功，大小: ${payload.size} 字節")
                    
                    val success = sendHealthReportPacket(payload)
                    if (success) {
                        result.success(true)
                    } else {
                        result.error("SERVICE_NOT_READY", "Mesh service is not running", null)
                    }
                } catch (e: Exception) {
                    result.error("SEND_FAILED", e.message, null)
                }
            }

            // 聊天的送出改由 ChatBridge 的 chat_sendMessage 轉呼叫 ChatViewModel（#9、#51）；
            // 附近的 peer 改由 ChatBridge 的 chat_peers 快照提供（#53，取代舊的 getNearbyPeers）。
            // 這裡不能再認領任何聊天 method：本類別先被詢問，會遮蔽 ChatBridge。

            else -> return false
        }
        return true
    }

    /**
     * `isRegistered`：身分儲存區裡有沒有 static key。在 [ioDispatcher] 上讀，回到主執行緒回覆；
     * 讀不到（例如 keystore 失效）時回 error；Dart 端不當作「尚未註冊」，改照本機資料與雲端繼續驗證（#71）。
     */
    private fun answerIsRegistered(result: MethodChannel.Result) {
        scope.launch {
            val registered = try {
                withContext(ioDispatcher) { identityManager.hasIdentityData() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.error("IDENTITY_UNAVAILABLE", e.message, null)
                return@launch
            }
            result.success(registered)
        }
    }

    /** 經共用 emitter 走 main looper 送出，不依賴 Activity 存活；送不出去時會留下 log。 */
    private fun emitEvent(event: Map<String, Any?>) {
        events.emit(event)
    }

    /**
     * 發送 HEALTH_REPORT 類型的 BitchatPacket
     * @param payload 已編碼的二進制健康報告數據
     * @return 如果發送成功返回 true，否則返回 false
     */
    private fun sendHealthReportPacket(payload: ByteArray): Boolean {
        Log.d("BitchatBridge", "🔄 正在發送 HEALTH_REPORT 封包，大小: ${payload.size}，TTL: ${HealthReportBroadcast.DEFAULT_TTL}")
        // 通過 BluetoothMeshService 廣播 HEALTH_REPORT 封包
        val sent = HealthReportBroadcast.send(MeshServiceHolder.meshService, payload)
        if (sent) {
            Log.d("BitchatBridge", "📤 HEALTH_REPORT 已提交給網格服務")
        } else {
            Log.e("BitchatBridge", "❌ Mesh 服務未啟動，無法發送 HEALTH_REPORT")
        }
        return sent
    }

    /**
     * 從 Flutter 傳來的 Broadcast Tier map 建構 payload。
     * 只接受不具識別性的欄位——reporterHandle、status（中文 label）、以及原始經緯度
     * （經緯度在此就地降精度為 geohash，精確值不會進入廣播封包）。
     * 任何 PII（姓名、電話、血型、自由文字）即使出現在 map 中也一律忽略。
     */
    private fun convertMapToHealthReportPayload(map: Map<*, *>): com.bitchat.android.protocol.HealthReportPayload? {
        return try {
            val handle = map["reporterHandle"] as? String ?: return null
            if (!com.bitchat.android.protocol.HealthReportPayload.HANDLE_REGEX.matches(handle)) return null
            val status = com.bitchat.android.protocol.HealthStatus.fromLabel(
                map["status"] as? String ?: return null
            ) ?: return null
            com.bitchat.android.protocol.HealthReportPayload.fromLocation(
                reporterHandle = handle,
                status = status,
                lat = (map["lat"] as? Number)?.toDouble(),
                lng = (map["lng"] as? Number)?.toDouble(),
                reportTimeMillis = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 由 engine 清理（`cleanUpFlutterEngine`）呼叫：解除 receiver、移除本實例的封包 listener、
     * 卸下兩個 channel 的 handler 並關閉 emitter。重複呼叫無害。
     */
    fun destroy() {
        scope.cancel()
        InboundPacketBridge.removeListener(packetListener)
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("BitchatBridge", "statusReceiver 已解除註冊")
        }
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        events.close()
    }

    companion object {
        const val METHOD_CHANNEL_NAME = "com.bitchat/bridge/methods"
        const val EVENT_CHANNEL_NAME = "com.bitchat/bridge/events"
    }
}
