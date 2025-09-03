import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

public class AndroidDecompressTest {
    public static void main(String[] args) {
        System.out.println("=== Android LZ4 Decompression Test ===");
        
        // iOS compressed data (from iOS test output)
        String iosCompressedHex = "62763431900100009b000000ff05434f4d5052455353494e4720444154412042524f1400e9f071444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f62763424";
        
        // Convert hex to bytes
        byte[] iosCompressedData = hexStringToByteArray(iosCompressedHex);
        System.out.println("iOS compressed data size: " + iosCompressedData.length + " bytes");
        System.out.println("iOS compressed data (first 32 bytes): " + bytesToHex(iosCompressedData, 32));
        
        // Expected original data
        String expectedMessage = "COMPRESSING DATA BRO".repeat(20);
        byte[] expectedData = expectedMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Expected original size: " + expectedData.length + " bytes");
        
        try {
            // Initialize LZ4 decompressor (same as Android CompressionUtil)
            LZ4Factory lz4Factory = LZ4Factory.fastestInstance();
            LZ4SafeDecompressor decompressor = lz4Factory.safeDecompressor();
            
            System.out.println("✅ LZ4 library loaded successfully");
            System.out.println("LZ4 Factory: " + lz4Factory.getClass().getSimpleName());
            System.out.println("LZ4 Decompressor: " + decompressor.getClass().getSimpleName());
            
            // Try to decompress iOS data with Android LZ4
            byte[] decompressedBuffer = new byte[expectedData.length];
            int actualSize = decompressor.decompress(iosCompressedData, decompressedBuffer);
            
            System.out.println("\n=== Decompression Results ===");
            System.out.println("Decompressed size: " + actualSize + " bytes");
            
            if (actualSize == expectedData.length) {
                String decompressedString = new String(decompressedBuffer, java.nio.charset.StandardCharsets.UTF_8);
                boolean isIdentical = java.util.Arrays.equals(decompressedBuffer, expectedData);
                
                System.out.println("Cross-platform compatibility: " + (isIdentical ? "✅ COMPATIBLE" : "❌ INCOMPATIBLE"));
                
                if (isIdentical) {
                    System.out.println("🎉 SUCCESS: Android can decompress iOS LZ4 data!");
                } else {
                    System.out.println("❌ FAILURE: Data doesn't match");
                    System.out.println("Expected: " + expectedMessage.substring(0, Math.min(50, expectedMessage.length())) + "...");
                    System.out.println("Got: " + decompressedString.substring(0, Math.min(50, decompressedString.length())) + "...");
                }
            } else {
                System.out.println("❌ FAILURE: Wrong decompressed size");
                System.out.println("Expected: " + expectedData.length + " bytes");
                System.out.println("Got: " + actualSize + " bytes");
            }
            
        } catch (Exception e) {
            System.out.println("❌ FAILURE: Exception during decompression");
            System.out.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    private static String bytesToHex(byte[] bytes, int maxBytes) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, maxBytes); i++) {
            result.append(String.format("%02x ", bytes[i]));
        }
        return result.toString().trim();
    }
}
