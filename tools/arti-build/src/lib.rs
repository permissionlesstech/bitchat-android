use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, GlobalRef};
use jni::sys::{jboolean, jint, jstring};
use jni::JavaVM;

use arti_client::{DormantMode, TorClient};
use arti_client::config::TorClientConfigBuilder;
use tor_rtcompat::PreferredRuntime;

use std::sync::{Arc, Mutex, Once};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
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

/// Handle to delayed soft-dormant task (re-enters DormantMode::Soft after background SOCKS activity)
static DORMANT_TIMER_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

/// Whether the host app currently wants Arti in soft-dormant mode (backgrounded or stopped)
static DORMANT_REQUESTED: AtomicBool = AtomicBool::new(false);

/// Number of SOCKS5 connections currently running `client.connect(...)`
static IN_FLIGHT_CONNECTS: AtomicUsize = AtomicUsize::new(0);

/// Number of currently open SOCKS5 streams
static ACTIVE_SOCKS_STREAMS: AtomicUsize = AtomicUsize::new(0);

/// Monotonic counter bumped on SOCKS connect/close activity to debounce re-dormancy
static SOCKS_ACTIVITY_EPOCH: AtomicU64 = AtomicU64::new(0);

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

fn cancel_dormant_timer() {
    if let Some(handle) = DORMANT_TIMER_TASK.lock().unwrap().take() {
        handle.abort();
    }
}

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
/// Upstream `arti-client` automatically wakes `DormantMode::Soft` back to `DormantMode::Normal`
/// whenever `client.connect(...)` is invoked. Re-applying `DormantMode::Soft` once in-flight
/// connects finish and remain quiet for `RE_DORMANT_IDLE_DELAY` prevents a single background
/// reconnect from leaving Arti's periodic circuit/directory/padding tasks running indefinitely.
fn schedule_re_dormant_if_requested() {
    if !DORMANT_REQUESTED.load(Ordering::SeqCst) {
        return;
    }
    let epoch = SOCKS_ACTIVITY_EPOCH.fetch_add(1, Ordering::SeqCst) + 1;
    cancel_dormant_timer();

    let handle = {
        let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
        let Some(runtime) = runtime_guard.as_ref() else {
            return;
        };

        runtime.spawn(async move {
            tokio::time::sleep(RE_DORMANT_IDLE_DELAY).await;
            if DORMANT_REQUESTED.load(Ordering::SeqCst)
                && IN_FLIGHT_CONNECTS.load(Ordering::SeqCst) == 0
                && SOCKS_ACTIVITY_EPOCH.load(Ordering::SeqCst) == epoch
            {
                if apply_dormant_mode_now(DormantMode::Soft) {
                    log_info!(
                        "Arti re-entered DormantMode::Soft after background SOCKS settle (active_streams={})",
                        ACTIVE_SOCKS_STREAMS.load(Ordering::SeqCst)
                    );
                }
            }
        })
    };

    *DORMANT_TIMER_TASK.lock().unwrap() = Some(handle);
}

struct ActiveStreamGuard;

impl ActiveStreamGuard {
    fn new() -> Self {
        ACTIVE_SOCKS_STREAMS.fetch_add(1, Ordering::SeqCst);
        Self
    }
}

impl Drop for ActiveStreamGuard {
    fn drop(&mut self) {
        let prev = ACTIVE_SOCKS_STREAMS.fetch_sub(1, Ordering::SeqCst);
        if prev == 1 && DORMANT_REQUESTED.load(Ordering::SeqCst) {
            schedule_re_dormant_if_requested();
        }
    }
}

struct InFlightConnectGuard;

impl InFlightConnectGuard {
    fn new() -> Self {
        IN_FLIGHT_CONNECTS.fetch_add(1, Ordering::SeqCst);
        SOCKS_ACTIVITY_EPOCH.fetch_add(1, Ordering::SeqCst);
        cancel_dormant_timer();
        Self
    }
}

impl Drop for InFlightConnectGuard {
    fn drop(&mut self) {
        let prev = IN_FLIGHT_CONNECTS.fetch_sub(1, Ordering::SeqCst);
        if prev == 1 && DORMANT_REQUESTED.load(Ordering::SeqCst) {
            schedule_re_dormant_if_requested();
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

    // Stop any existing SOCKS server first
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting previous SOCKS server task");
        handle.abort();
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

    // Ensure client wakes from any prior stop()/dormant state while starting SOCKS proxy
    DORMANT_REQUESTED.store(false, Ordering::SeqCst);
    cancel_dormant_timer();
    client.set_dormant(DormantMode::Normal);

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    // Try to bind IMMEDIATELY to detect port conflicts before returning
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
            return -3;
        }
    };

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

                    tokio::spawn(async move {
                        if let Err(e) = handle_socks_connection(stream, client_clone).await {
                            log_error!("SOCKS connection error: {:?}", e);
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
        let _connect_guard = InFlightConnectGuard::new();
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
    DORMANT_REQUESTED.store(want_dormant, Ordering::SeqCst);
    cancel_dormant_timer();

    if !want_dormant {
        if apply_dormant_mode_now(DormantMode::Normal) {
            log_info!("Arti dormant mode set to Normal");
            0
        } else {
            -1
        }
    } else if IN_FLIGHT_CONNECTS.load(Ordering::SeqCst) > 0 {
        // Let active Tor connects finish before transitioning to soft-dormant mode.
        schedule_re_dormant_if_requested();
        0
    } else if apply_dormant_mode_now(DormantMode::Soft) {
        log_info!(
            "Arti dormant mode set to Soft (active_streams={})",
            ACTIVE_SOCKS_STREAMS.load(Ordering::SeqCst)
        );
        0
    } else {
        -1
    }
}

/// Stop Arti and cleanup
#[no_mangle]
pub extern "C" fn Java_org_torproject_arti_ArtiNative_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    log_info!("AMEx: state changed to Stopping");
    log_info!("Stopping Arti...");

    // Cancel any pending re-dormant timer and put the resident TorClient to sleep
    DORMANT_REQUESTED.store(true, Ordering::SeqCst);
    cancel_dormant_timer();

    // Abort SOCKS proxy task (releases the port)
    if let Some(handle) = SOCKS_TASK.lock().unwrap().take() {
        log_info!("Aborting SOCKS server task");
        handle.abort();
    }

    // Put the resident TorClient into DormantMode::Soft so it does not keep burning CPU
    // on background directory/circuit/padding tasks while Tor is stopped.
    if apply_dormant_mode_now(DormantMode::Soft) {
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
