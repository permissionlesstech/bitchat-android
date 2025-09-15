package com.bitchat.android.nostr;

import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointUtil;

import java.math.BigInteger;
import java.security.Security;

/**
 * Implémentation des opérations de cryptographie Nostr (secp256k1, Schnorr).
 */
public class NostrCrypto {

    private static final ECDomainParameters EC_DOMAIN_PARAMETERS;

    static {
        Security.addProvider(new BouncyCastleProvider());
        ECParameterSpec ecSpec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1");
        EC_DOMAIN_PARAMETERS = new ECDomainParameters(ecSpec.getCurve(), ecSpec.getG(), ecSpec.getN(), ecSpec.getH());
        FixedPointUtil.precompute(EC_DOMAIN_PARAMETERS.getG());
    }

    public static String schnorrSign(byte[] hash, String privateKeyHex) {
        try {
            BigInteger privateKey = new BigInteger(1, hexToBytes(privateKeyHex));
            ECDSASigner signer = new ECDSASigner();
            signer.init(true, new ECPrivateKeyParameters(privateKey, EC_DOMAIN_PARAMETERS));
            BigInteger[] signature = signer.generateSignature(hash);
            return bytesToHex(signature[0].toByteArray()) + bytesToHex(signature[1].toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean schnorrVerify(byte[] hash, String signatureHex, String pubkeyHex) {
        try {
            byte[] pubkeyBytes = hexToBytes(pubkeyHex);
            ECPoint pubkeyPoint = EC_DOMAIN_PARAMETERS.getCurve().decodePoint(pubkeyBytes);
            ECPublicKeyParameters publicKey = new ECPublicKeyParameters(pubkeyPoint, EC_DOMAIN_PARAMETERS);

            byte[] signatureBytes = hexToBytes(signatureHex);
            BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(signatureBytes, 0, 32));
            BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signatureBytes, 32, 64));

            ECDSASigner signer = new ECDSASigner();
            signer.init(false, publicKey);
            return signer.verifySignature(hash, r, s);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidPublicKey(String pubkeyHex) {
        try {
            byte[] pubkeyBytes = hexToBytes(pubkeyHex);
            EC_DOMAIN_PARAMETERS.getCurve().decodePoint(pubkeyBytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
