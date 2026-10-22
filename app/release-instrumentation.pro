# Keep only the real analyzer entry point for tests. Do not keep ML Kit internals:
# doing so would hide regressions in the production dependency-discovery graph.
-keep class com.bitchat.android.ui.QRCodeAnalyzer {
    <init>(kotlin.jvm.functions.Function1);
    public void analyze(androidx.camera.core.ImageProxy);
}
# CameraX's public frame interfaces are implemented by the synthetic test source.
-keep interface androidx.camera.core.ImageProxy { *; }
-keep interface androidx.camera.core.ImageInfo { *; }
