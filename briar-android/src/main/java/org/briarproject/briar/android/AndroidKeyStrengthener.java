package org.briarproject.briar.android;

import android.security.keystore.KeyGenParameterSpec;

import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;

import androidx.annotation.RequiresApi;

import static android.os.Build.VERSION.SDK_INT;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256;
import static android.security.keystore.KeyProperties.PURPOSE_SIGN;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;

@RequiresApi(23)
@NotNullByDefault
class AndroidKeyStrengthener implements KeyStrengthener {

	private static final Logger LOG =
			getLogger(AndroidKeyStrengthener.class.getName());

	private static final String KEY_STORE_TYPE = "AndroidKeyStore";
	private static final String PROVIDER_NAME = "AndroidKeyStore";
	private static final String KEY_ALIAS = "db";
	private static final int KEY_BITS = 256;

	private final List<AlgorithmParameterSpec> specs;

	AndroidKeyStrengthener() {
		KeyGenParameterSpec noStrongBox =
				new KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
						.setKeySize(KEY_BITS)
						.build();
		if (SDK_INT >= 28) {
			// Prefer StrongBox if available
			KeyGenParameterSpec strongBox =
					new KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
							.setIsStrongBoxBacked(true)
							.setKeySize(KEY_BITS)
							.build();
			specs = asList(strongBox, noStrongBox);
		} else {
			specs = singletonList(noStrongBox);
		}
	}

	@GuardedBy("this")
	@Nullable
	private javax.crypto.SecretKey storedKey = null;

	@Override
	public synchronized boolean isInitialised() {
		if (storedKey != null) return true;
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			Entry entry = ks.getEntry(KEY_ALIAS, null);
			if (entry instanceof SecretKeyEntry) {
				storedKey = ((SecretKeyEntry) entry).getSecretKey();
				LOG.info("Loaded key from keystore");
				return true;
			}
			return false;
		} catch (GeneralSecurityException e) {
			logException(LOG, WARNING, e);
			return false;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public synchronized SecretKey strengthenKey(SecretKey k) {
		try {
			if (!isInitialised()) initialise();
			// Use the input key and the stored key to derive the output key
			Mac mac = Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256);
			mac.init(storedKey);
			return new SecretKey(mac.doFinal(k.getBytes()));
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	private synchronized void initialise() throws GeneralSecurityException {
		// Try the parameter specs in order of preference
		for (AlgorithmParameterSpec spec : specs) {
			try {
				KeyGenerator kg = KeyGenerator.getInstance(
						KEY_ALGORITHM_HMAC_SHA256, PROVIDER_NAME);
				kg.init(spec);
				storedKey = kg.generateKey();
				LOG.info("Stored key in keystore");
				return;
			} catch (Exception e) {
				if (LOG.isLoggable(INFO))
					LOG.info("Could not generate key: " + e);
				// Fall back to next spec
			}
		}
		throw new GeneralSecurityException("Could not generate key");
	}
}
