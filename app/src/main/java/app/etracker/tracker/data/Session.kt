package app.etracker.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The person carrying this device: personnel number, name, appointment (Commander, Deputy, Signaller, Other). */
data class Holder(
    val personnelNumber: String,
    val name: String,
    val appointment: String,
    /** Not found in the roster (or in another unit): accepted, but flagged on the server. */
    val unverified: Boolean = false,
    /** This device positions the unit on the map. */
    val primary: Boolean = false,
) {
    /** Commander and Deputy may issue orders, respond to and close reports and chalk off tasks. */
    val canCommand: Boolean get() = appointment == "Commander" || appointment == "Deputy"
}

/** What the server returns when a device enrols. */
data class Enrolment(
    val token: String,
    val device: String,
    val label: String,
    val unit: String,
    val sidc: String,
    val intervalSeconds: Int,
    val holder: Holder? = null,
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

    val holder: Holder?
        get() = prefs.getString("holder_pn", null)?.takeIf { it.isNotBlank() }?.let {
            Holder(
                personnelNumber = it,
                name = prefs.getString("holder_name", "") ?: "",
                appointment = prefs.getString("holder_appointment", "Other") ?: "Other",
                unverified = prefs.getBoolean("holder_unverified", false),
                primary = prefs.getBoolean("holder_primary", false),
            )
        }

    /** The holder last used on this phone: pre-fills the form when signing in again (cleared by a remote wipe). */
    val lastHolder: Holder?
        get() = prefs.getString("last_pn", null)?.takeIf { it.isNotBlank() }?.let {
            Holder(it, prefs.getString("last_holder_name", "") ?: "", prefs.getString("last_holder_appointment", "Other") ?: "Other")
        }

    /** What the Situation poll says about this device now (appointment edits, primary changes). */
    fun updateHolder(name: String, appointment: String, primary: Boolean, unverified: Boolean) {
        if (holder == null) return
        prefs.edit()
            .putString("holder_name", name)
            .putString("holder_appointment", appointment)
            .putBoolean("holder_primary", primary)
            .putBoolean("holder_unverified", unverified)
            .apply()
    }

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
            .apply {
                val h = e.holder
                if (h != null) {
                    putString("holder_pn", h.personnelNumber)
                    putString("holder_name", h.name)
                    putString("holder_appointment", h.appointment)
                    putBoolean("holder_unverified", h.unverified)
                    putBoolean("holder_primary", h.primary)
                    putString("last_pn", h.personnelNumber)
                    putString("last_holder_name", h.name)
                    putString("last_holder_appointment", h.appointment)
                } else {
                    remove("holder_pn")
                }
            }
            .apply()
    }

    /** Server rejected our token: forget it (keeps the server address). */
    fun markRevoked() {
        prefs.edit().remove("token").putBoolean("revoked", true).apply()
    }

    fun clear() {
        val keep = site
        val h = lastHolder
        prefs.edit().clear().putString("last_site", keep).apply {
            if (h != null) {
                putString("last_pn", h.personnelNumber)
                putString("last_holder_name", h.name)
                putString("last_holder_appointment", h.appointment)
            }
        }.apply()
    }

    /** Remote wipe: forget everything, including the server address. */
    fun wipe() {
        prefs.edit().clear().commit()
    }

    fun updateInterval(seconds: Int) {
        prefs.edit().putInt("interval", seconds).apply()
    }
}
