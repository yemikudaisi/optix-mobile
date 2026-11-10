package app.etracker.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** What the server returns when a device enrols. */
data class Enrolment(
    val token: String,
    val device: String,
    val label: String,
    val unit: String,
    val sidc: String,
    val intervalSeconds: Int,
)

/**
 * The enrolled-device identity, kept in EncryptedSharedPreferences (the token
 * never touches disk in clear text). The server only ever stores a hash of it.
 */
class Session(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "device_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var site: String?
        get() = prefs.getString("site", null)
        private set(v) = prefs.edit().putString("site", v).apply()

    /** The last server used — survives sign-out so re-enrolling is quicker. */
    val lastSite: String? get() = prefs.getString("last_site", null)

    val token: String? get() = prefs.getString("token", null)
    val device: String get() = prefs.getString("device", "") ?: ""
    val label: String get() = prefs.getString("label", "") ?: ""
    val unit: String get() = prefs.getString("unit", "") ?: ""
    val sidc: String get() = prefs.getString("sidc", "") ?: ""
    val intervalSeconds: Int get() = prefs.getInt("interval", 30).coerceIn(5, 3600)

    val enrolled: Boolean get() = !token.isNullOrBlank() && !site.isNullOrBlank()

    /** True when the server told us this device was revoked. */
    var revoked: Boolean
        get() = prefs.getBoolean("revoked", false)
        set(v) = prefs.edit().putBoolean("revoked", v).apply()

    fun save(site: String, e: Enrolment) {
        prefs.edit()
            .putString("site", site)
            .putString("last_site", site)
            .putString("token", e.token)
            .putString("device", e.device)
            .putString("label", e.label)
            .putString("unit", e.unit)
            .putString("sidc", e.sidc)
            .putInt("interval", e.intervalSeconds)
            .putBoolean("revoked", false)
            .apply()
    }

    /** Server rejected our token: forget it (keeps the server address). */
    fun markRevoked() {
        prefs.edit().remove("token").putBoolean("revoked", true).apply()
    }

    fun clear() {
        val keep = site
        prefs.edit().clear().putString("last_site", keep).apply()
    }

    /** Remote wipe: forget everything, including the server address. */
    fun wipe() {
        prefs.edit().clear().commit()
    }

    fun updateInterval(seconds: Int) {
        prefs.edit().putInt("interval", seconds).apply()
    }
}
