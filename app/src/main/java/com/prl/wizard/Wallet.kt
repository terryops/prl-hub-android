package com.prl.wizard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The wallet secret (BRIDGE.md "The wallet secret"): 32 random bytes, AES-256-GCM encrypted
 * under an Android Keystore key without user-authentication binding, kept in a SharedPreferences
 * file that backups exclude. Blocking Keystore calls: use off the main thread.
 */
class WalletSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    data class Access internal constructor(val hasSecret: Boolean, internal val generation: Long)

    // These status reads also run on the UI thread; never wait behind a Keystore operation.
    fun hasSecret(): Boolean = prefs.contains(CIPHERTEXT) && prefs.contains(IV)

    /** Snapshot before the authentication prompt; clear invalidates every outstanding prompt. */
    fun access(): Access {
        val requestedGeneration = generation
        return Access(hasSecret(), requestedGeneration)
    }

    /** Never create for a read-only unlock, or after a reset that happened during authentication. */
    fun resolve(create: Boolean, access: Access): String? = synchronized(lock) {
        if (access.generation != generation) return@synchronized null
        if (hasSecret()) read() else if (create) create() else null
    }

    fun create(): String = synchronized(lock) {
        // Another activity/bridge may have created it since the caller checked for its existence.
        if (hasSecret()) return@synchronized read()
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        keyStore.deleteEntry(ALIAS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, generateKey())
        cipher.updateAAD(ALIAS.toByteArray())
        val ciphertext = cipher.doFinal(secret)
        val saved = prefs.edit().putString(IV, encode(cipher.iv)).putString(CIPHERTEXT, encode(ciphertext)).commit()
        check(saved) { "secret not saved" }
        encode(secret).also { secret.fill(0) }
    }

    fun read(): String = synchronized(lock) {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: error("key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(prefs.getString(IV, null)!!)))
        cipher.updateAAD(ALIAS.toByteArray())
        val secret = cipher.doFinal(decode(prefs.getString(CIPHERTEXT, null)!!))
        encode(secret).also { secret.fill(0) }
    }

    fun clear() = synchronized(lock) {
        generation++
        prefs.edit(commit = true) { clear() }
        keyStore.deleteEntry(ALIAS)
    }

    /** StrongBox when the device has it, else the TEE. */
    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return generateKey(strongBox = true)
            } catch (e: Exception) {
                Log.i(TAG, "StrongBox unavailable (${e.javaClass.simpleName}); using the TEE")
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec.build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        private const val TAG = "PearlWallet"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "pearl.wallet.secret.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS = "wallet_secret"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
        private val lock = Any()
        @Volatile private var generation = 0L
    }
}

/**
 * Asks the person to confirm with biometrics or the screen lock: BiometricPrompt on API 28+,
 * the keyguard confirm-credential screen on 26–27. One prompt at a time; `done` gets null on
 * success or a bridge error code.
 */
class WalletAuth(private val activity: FragmentActivity, private val confirmCredential: ActivityResultLauncher<Intent>) {
    private var pending: ((String?) -> Unit)? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(errorCodeFor(errorCode))
        },
    )

    fun deviceSecure(): Boolean = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun authenticate(title: String, subtitle: String?, done: (String?) -> Unit) {
        if (pending != null) return done("failed")
        // AndroidX silently returns without a callback once the fragment state has been saved.
        if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return done("cancelled")
        if (!deviceSecure()) return done("no_lock_screen")
        pending = done
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val authenticators = if (Build.VERSION.SDK_INT >= 30) {
                    Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                } else {
                    Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
                }
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle?.ifEmpty { null })
                    .setAllowedAuthenticators(authenticators)
                    .build()
                prompt.authenticate(info)
            } else {
                @Suppress("DEPRECATION")
                val intent = activity.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) finish("no_lock_screen") else confirmCredential.launch(intent)
            }
        } catch (error: RuntimeException) {
            Log.w("PearlWallet", "Authentication could not start", error)
            finish("failed")
        }
    }

    /** Result of the API 26–27 confirm-credential screen. */
    fun onConfirmCredentialResult(resultCode: Int) = finish(if (resultCode == Activity.RESULT_OK) null else "cancelled")

    private fun finish(error: String?) {
        val done = pending ?: return
        pending = null
        done(error)
    }

    private fun errorCodeFor(code: Int): String = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT -> "cancelled"
        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> "no_lock_screen"
        BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_BIOMETRICS -> "unavailable"
        else -> "failed"
    }
}
