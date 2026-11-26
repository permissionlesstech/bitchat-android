use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, GlobalRef};
use jni::sys::{jboolean, jint, jstring};
use jni::JavaVM;

use arti_client::{DormantMode, TorClient};
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;

use std::sync::{Arc, Mutex, Once};
use std::path::PathBuf;
use std::time::Duration;
use anyhow::Result;

// ============================================================================
// Global State
// ============================================================================

/// Global Arti client instance
static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);

/// Global Tokio runtime (must persist for Arti to work)
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

/// Global JavaVM reference (cached on first JNI call)
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);

/// Global log callback reference
static LOG_CALLBACK: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// Handle to SOCKS server task (for graceful shutdown)
static SOCKS_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

/// Serialized dormant-mode state preventing TOCTOU races between foreground wake
/// (`setDormant(false)`), background sleep (`setDormant(true)`), `startSocksProxy`,
/// `stop`, and the delayed background re-dormant timer.
struct DormantState {
    requested: bool,
    socks_running: bool,
    in_flight_connects: usize,
    active_streams: usize,
    activity_epoch: u64,
    timer: Option<tokio::task::JoinHandle<()>>,
    socks_shutdown_tx: Option<tokio::sync::watch::Sender<bool>>,
}

impl DormantState {
    const fn new() -> Self {
        Self {
            requested: false,
            socks_running: false,
            in_flight_connects: 0,
            active_streams: 0,
            activity_epoch: 0,
            timer: None,
            socks_shutdown_tx: None,
        }
    }
}

static DORMANT_STATE: Mutex<DormantState> = Mutex::new(DormantState::new());

/// Grace period after background SOCKS connect activity before returning to DormantMode::Soft
const RE_DORMANT_IDLE_DELAY: Duration = Duration::from_secs(15);

/// Initialization flag
static INIT_ONCE: Once = Once::new();

// ============================================================================
// Logging Integration
// ============================================================================

/// Send log message to Java callback
fn send_log_to_java(message: String) {
    let vm_opt = JAVA_VM.lock().unwrap();
    let callback_opt = LOG_CALLBACK.lock().unwrap();

    if let (Some(vm), Some(callback)) = (vm_opt.as_ref(), callback_opt.as_ref()) {
        if let Ok(mut env) = vm.attach_current_thread() {
            if let Ok(jmessage) = env.new_string(&message) {
                let _ = env.call_method(
                    callback.as_obj(),
                    "onLogLine",
                    "(Ljava/lang/String;)V",
                    &[(&jmessage).into()]
                );
            }
        }
    }
}

/// Macro for logging to both Android logcat and Java callback
macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        android_logger::log(&format!("Arti: {}", msg));
        send_log_to_java(msg);
    }};
}

// ============================================================================
// Dormant Mode Helpers
// ============================================================================

fn apply_dormant_mode_now(mode: DormantMode) -> bool {
    let client_opt = ARTI_CLIENT.lock().unwrap().as_ref().map(Arc::clone);
    if let Some(client) = client_opt {
        client.set_dormant(mode);
        true
    } else {
        false
    }
}

/// Schedule a debounced return to `DormantMode::Soft` when backgrounded.
///
/// Must be called while holding `DORMANT_STATE`. Upstream `arti-client` automatically wakes
/// `DormantMode::Soft` back to `DormantMode::Normal` whenever `client.connect(...)` runs;
/// re-checking and applying `DormantMode::Soft` under `DORMANT_STATE` guarantees a foreground
/// wake (`setDormant(false)`) can never be overwritten by a racing background timer.
fn schedule_re_dormant_locked(state: &mut DormantState) {
    if !state.requested {
        return;
    }
    state.activity_epoch = state.activity_epoch.wrapping_add(1);
    let epoch = state.activity_epoch;
    if let Some(handle) = state.timer.take() {
        handle.abort();
    }

    let handle = {
        let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
        let Some(runtime) = runtime_guard.as_ref() else {
            return;
        };

        runtime.spawn(async move {
            tokio::time::sleep(RE_DORMANT_IDLE_DELAY).await;
            let applied_streams = {
                let mut state = DORMANT_STATE.lock().unwrap();
                if state.requested
                    && state.in_flight_connects == 0
                    && state.activity_epoch == epoch
                {
                    state.timer = None;
                    if apply_dormant_mode_now(DormantMode::Soft) {
                        Some(state.active_streams)
                    } else {
                        None
                    }
                } else {
                    None
                }
            };
            if let Some(streams) = applied_streams {
                log_info!(
                    "Arti re-entered DormantMode::Soft after background SOCKS settle (active_streams={})",
                    streams
                );
            }
        })
    };

    state.timer = Some(handle);
}

struct ActiveStreamGuard;

impl ActiveStreamGuard {
    fn new() -> Self {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.active_streams += 1;
        Self
    }
}

impl Drop for ActiveStreamGuard {
    fn drop(&mut self) {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.active_streams = state.active_streams.saturating_sub(1);
        if state.active_streams == 0 && state.requested && state.in_flight_connects == 0 {
            schedule_re_dormant_locked(&mut state);
        }
    }
}

struct InFlightConnectGuard;

impl InFlightConnectGuard {
    /// Register an in-flight `client.connect(...)` only if the SOCKS server is still active.
    /// Returns `None` if `stop()` has already stopped the proxy, preventing a delayed handshake
    /// from waking the resident `TorClient` out of `DormantMode::Soft`.
    fn try_new() -> Option<Self> {
        let mut state = DORMANT_STATE.lock().unwrap();
        if !state.socks_running {
            return None;
        }
        state.in_flight_connects += 1;
        state.activity_epoch = state.activity_epoch.wrapping_add(1);
        if let Some(handle) = state.timer.take() {
            handle.abort();
        }
        Some(Self)
    }
}

impl Drop for InFlightConnectGuard {
    fn drop(&mut self) {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.in_flight_connects = state.in_flight_connects.saturating_sub(1);
        if state.in_flight_connects == 0 {
            if !state.socks_running {
                // Proxy was stopped while this connect was in flight; immediately re-apply Soft.
                let _ = apply_dormant_mode_now(DormantMode::Soft);
            } else if state.requested {
                schedule_re_dormant_locked(&mut state);
            }
        }
    }
}

// ============================================================================
// JNI Functions
// ============================================================================

/// Get Arti version string
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // Cache JavaVM on first call
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let version = format!("Arti {} (custom build with rustls)", env!("CARGO_PKG_VERSION"));
    let output = env.new_string(version).expect("Couldn't create java string!");
    output.into_raw()
}

/// Set log callback for Arti logs
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_setLogCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    // Store global reference to callback
    if let Ok(global_ref) = env.new_global_ref(callback) {
        *LOG_CALLBACK.lock().unwrap() = Some(global_ref);
        log_info!("Log callback registered");
    }
}

/// Initialize Arti runtime
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_initialize(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jint {
    // Cache JavaVM if not already cached
    if JAVA_VM.lock().unwrap().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            *JAVA_VM.lock().unwrap() = Some(vm);
        }
    }

    let data_dir_str: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            log_error!("Failed to convert data_dir: {:?}", e);
            return -1;
        }
    };

    log_info!("AMEx: state changed to Initialized");
    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    // Initialize Tokio runtime (once)
    INIT_ONCE.call_once(|| {
        match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                log_info!("Tokio runtime created successfully");
                *TOKIO_RUNTIME.lock().unwrap() = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    // Check if runtime exists
    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Create config with explicit Android paths
    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    // Create directories if they don't exist
    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let result: Result<()> = runtime.block_on(async {
        log_info!("Creating Arti client...");
        log_info!("Cache dir: {:?}", cache_dir);
        log_info!("State dir: {:?}", state_dir);

        // Create config with Android-specific directories
        let config = TorClientConfigBuilder::from_directories(state_dir, cache_dir)
            .build()?;

        // Create client with Android-specific config
        let client = TorClient::create_bootstrapped(config).await?;

        log_info!("Arti client created successfully");

        // Store client globally
        *ARTI_CLIENT.lock().unwrap() = Some(Arc::new(client));

        Ok(())
    });

    match result {
        Ok(_) => {
            log_info!("Arti initialized successfully");
            0
        }
        Err(e) => {
            log_error!("Failed to initialize Arti: {:?}", e);
            -3
        }
    }
}

/// Start SOCKS proxy on specified port
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_startSocksProxy(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jint {
    log_info!("AMEx: state changed to Starting");
    log_info!("Starting SOCKS proxy on port {}", port);

    // Stop any existing SOCKS server and active handlers first
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting previous SOCKS server task");
        handle.abort();
    }
    {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.socks_running = false;
        if let Some(tx) = state.socks_shutdown_tx.take() {
            let _ = tx.send(true);
        }
    }

    let client_guard = ARTI_CLIENT.lock().unwrap();
    let client = match client_guard.as_ref() {
        Some(c) => Arc::clone(c),
        None => {
            log_error!("Arti client not initialized - call initialize() first");
            return -1;
        }
    };
    drop(client_guard);

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Try to bind IMMEDIATELY before waking TorClient so bind failures leave client dormant.
    let addr = format!("127.0.0.1:{}", port);

    // Use block_on to synchronously attempt binding
    let bind_result = runtime.block_on(async {
        tokio::net::TcpListener::bind(&addr).await
    });

    let listener = match bind_result {
        Ok(l) => {
            log_info!("SOCKS proxy bound to {}", addr);
            l
        }
        Err(e) => {
            log_error!("Failed to bind SOCKS proxy to {}: {:?}", addr, e);
            let mut state = DORMANT_STATE.lock().unwrap();
            state.requested = true;
            state.socks_running = false;
            state.activity_epoch = state.activity_epoch.wrapping_add(1);
            if let Some(handle) = state.timer.take() {
                handle.abort();
            }
            client.set_dormant(DormantMode::Soft);
            return -3;
        }
    };

    // Bind succeeded: mark proxy running, install shutdown channel, and wake TorClient to Normal.
    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
    {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.requested = false;
        state.socks_running = true;
        state.socks_shutdown_tx = Some(shutdown_tx);
        state.activity_epoch = state.activity_epoch.wrapping_add(1);
        if let Some(handle) = state.timer.take() {
            handle.abort();
        }
        client.set_dormant(DormantMode::Normal);
    }

    // Now spawn the background task with the already-bound listener
    let handle = runtime.spawn(async move {
        log_info!("SOCKS proxy listening on {}", addr);
        log_info!("Sufficiently bootstrapped; system SOCKS now functional");

        // Signal bootstrap completion to bitchat-android (expected by ArtiTorManager)
        // This sets bootstrapPercent to 100% and stops inactivity restarts
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
        log_info!("We have found that guard [scrubbed] is usable.");

        // Accept connections
        loop {
            match listener.accept().await {
                Ok((stream, peer_addr)) => {
                    log_info!("SOCKS connection from: {}", peer_addr);
                    let client_clone = Arc::clone(&client);
                    let mut conn_shutdown_rx = shutdown_rx.clone();

                    tokio::spawn(async move {
                        tokio::select! {
                            res = handle_socks_connection(stream, client_clone) => {
                                if let Err(e) = res {
                                    log_error!("SOCKS connection error: {:?}", e);
                                }
                            }
                            _ = conn_shutdown_rx.changed() => {
                                log_info!("SOCKS connection aborted on proxy stop");
                            }
                        }
                    });
                }
                Err(e) => {
                    log_error!("Failed to accept SOCKS connection: {:?}", e);
                    break; // Exit loop on error
                }
            }
        }

        log_info!("SOCKS proxy task exiting");
    });

    // Store handle for cleanup
    *SOCKS_TASK.lock().unwrap() = Some(handle);

    log_info!("SOCKS proxy started on port {}", port);
    0
}

/// Handle a single SOCKS connection
async fn handle_socks_connection(
    mut stream: tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let _stream_guard = ActiveStreamGuard::new();

    // Simple SOCKS5 handshake
    let mut buf = [0u8; 512];

    // Read version + methods
    let n = stream.read(&mut buf).await?;
    if n < 2 {
        return Err(anyhow::anyhow!("Invalid SOCKS handshake"));
    }

    // Send "no auth required" response
    stream.write_all(&[0x05, 0x00]).await?;

    // Read request
    let n = stream.read(&mut buf).await?;
    if n < 10 {
        return Err(anyhow::anyhow!("Invalid SOCKS request"));
    }

    // Parse SOCKS5 request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR DST.PORT(2)
    let version = buf[0];
    let cmd = buf[1];
    let atyp = buf[3];

    if version != 0x05 {
        return Err(anyhow::anyhow!("Unsupported SOCKS version: {}", version));
    }

    if cmd != 0x01 {
        // Only support CONNECT command
        stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Err(anyhow::anyhow!("Unsupported SOCKS command: {}", cmd));
    }

    // Parse target address and port
    let (target_host, target_port) = match atyp {
        0x01 => {
            // IPv4: 4 bytes
            let ip = format!("{}.{}.{}.{}", buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip, port)
        }
        0x03 => {
            // Domain name: length byte + domain
            let len = buf[4] as usize;
            if n < 5 + len + 2 {
                return Err(anyhow::anyhow!("Invalid domain name length"));
            }
            let domain = String::from_utf8_lossy(&buf[5..5 + len]).to_string();
            let port = u16::from_be_bytes([buf[5 + len], buf[5 + len + 1]]);
            (domain, port)
        }
        0x04 => {
            // IPv6: 16 bytes + 2 bytes port = 22 bytes total
            if n < 22 {
                stream.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
                return Err(anyhow::anyhow!("Truncated IPv6 request"));
            }
            let ip = format!(
                "{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}:{:02x}{:02x}",
                buf[4], buf[5], buf[6], buf[7], buf[8], buf[9], buf[10], buf[11],
                buf[12], buf[13], buf[14], buf[15], buf[16], buf[17], buf[18], buf[19]
            );
            let port = u16::from_be_bytes([buf[20], buf[21]]);
            (ip, port)
        }
        _ => {
            stream.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(anyhow::anyhow!("Unsupported address type: {}", atyp));
        }
    };

    log_info!("SOCKS5 CONNECT to {}:{}", target_host, target_port);

    // Establish Tor connection (automatically wakes DormantMode::Soft inside arti-client;
    // when this guard drops, if background dormancy is still requested, we schedule a debounced
    // return to DormantMode::Soft once all in-flight connects finish).
    let tor_stream = {
        let Some(_connect_guard) = InFlightConnectGuard::try_new() else {
            let _ = stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
            return Err(anyhow::anyhow!("SOCKS proxy stopped before connect"));
        };
        match client.connect((target_host.as_str(), target_port)).await {
            Ok(s) => s,
            Err(e) => {
                log_error!("Failed to connect through Tor: {:?}", e);
                // Send SOCKS5 error: general failure
                stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
                return Err(e.into());
            }
        }
    };

    log_info!("Tor connection established to {}:{}", target_host, target_port);

    // Send SOCKS5 success response
    stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;

    // Bidirectional data forwarding
    let (mut client_read, mut client_write) = stream.split();
    let (mut tor_read, mut tor_write) = tor_stream.split();

    let client_to_tor = async {
        tokio::io::copy(&mut client_read, &mut tor_write).await
    };

    let tor_to_client = async {
        tokio::io::copy(&mut tor_read, &mut client_write).await
    };

    // Run both directions concurrently, exit when either completes
    tokio::select! {
        result = client_to_tor => {
            if let Err(ref e) = result {
                log_error!("Client->Tor copy error: {:?}", e);
            }
        }
        result = tor_to_client => {
            if let Err(ref e) = result {
                log_error!("Tor->Client copy error: {:?}", e);
            }
        }
    };

    log_info!("SOCKS connection closed for {}:{}", target_host, target_port);

    Ok(())
}

/// Toggle Arti dormant mode (`DormantMode::Soft` when `dormant != 0`, `DormantMode::Normal` otherwise).
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_setDormant(
    _env: JNIEnv,
    _class: JClass,
    dormant: jboolean,
) -> jint {
    let want_dormant = dormant != 0;
    let (rc, log_msg) = {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.requested = want_dormant;
        state.activity_epoch = state.activity_epoch.wrapping_add(1);
        if let Some(handle) = state.timer.take() {
            handle.abort();
        }

        if !want_dormant {
            if apply_dormant_mode_now(DormantMode::Normal) {
                (0, Some("Arti dormant mode set to Normal".to_string()))
            } else {
                (-1, None)
            }
        } else if state.in_flight_connects > 0 {
            // Let active Tor connects finish before transitioning to soft-dormant mode.
            schedule_re_dormant_locked(&mut state);
            (0, None)
        } else if apply_dormant_mode_now(DormantMode::Soft) {
            (
                0,
                Some(format!(
                    "Arti dormant mode set to Soft (active_streams={})",
                    state.active_streams
                )),
            )
        } else {
            (-1, None)
        }
    };

    if let Some(msg) = log_msg {
        log_info!("{}", msg);
    }
    rc
}

/// Stop Arti and cleanup
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    // Abort SOCKS proxy task (releases the port)
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting SOCKS server task");
        handle.abort();
    }

    // Stop accepted SOCKS handlers, cancel any pending re-dormant timer, and put the resident
    // TorClient into DormantMode::Soft atomically under DORMANT_STATE.
    let slept = {
        let mut state = DORMANT_STATE.lock().unwrap();
        state.socks_running = false;
        state.requested = true;
        state.activity_epoch = state.activity_epoch.wrapping_add(1);
        if let Some(tx) = state.socks_shutdown_tx.take() {
            let _ = tx.send(true);
        }
        if let Some(handle) = state.timer.take() {
            handle.abort();
        }
        apply_dormant_mode_now(DormantMode::Soft)
    };
    if slept {
        log_info!("Resident Arti client set to DormantMode::Soft on stop");
    }

    // Give the abort a moment to complete and release the port
    if let Some(rt) = TOKIO_RUNTIME.lock().unwrap().as_ref() {
        rt.block_on(async {
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        });
    }

    // NOTE: We do NOT clear ARTI_CLIENT here!
    // The TorClient can be reused for multiple SOCKS proxy start/stop cycles.
    // Only clear it if you want to force full reinitialization.

    // Uncomment this line only if you want to force reinitialization on every start:
    // *ARTI_CLIENT.lock().unwrap() = None;

    log_info!("AMEx: state changed to Stopped");
    log_info!("Arti stopped successfully");

    0
}

// ============================================================================
// Android Logger (simple implementation)
// ============================================================================

mod android_logger {
    use std::ffi::CString;

    #[allow(non_camel_case_types)]
    type c_int = i32;

    #[allow(non_camel_case_types)]
    type c_char = i8;

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    const ANDROID_LOG_INFO: c_int = 4;

    pub fn log(message: &str) {
        unsafe {
            let tag = CString::new("ArtiNative").unwrap();
            let text = CString::new(message).unwrap();
            __android_log_write(ANDROID_LOG_INFO, tag.as_ptr() as *const c_char, text.as_ptr() as *const c_char);
        }
    }
}
