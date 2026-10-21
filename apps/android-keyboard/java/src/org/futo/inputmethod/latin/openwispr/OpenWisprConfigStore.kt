package org.futo.inputmethod.latin.openwispr

import android.content.Context
import app.openwispr.OpenWisprConfig
import app.openwispr.OpenWisprProvider
import app.openwispr.SecretBox
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.setSetting

object OpenWisprSettingKeys {
    val PROVIDER = SettingsKey(stringPreferencesKey("openwispr_provider"), OpenWisprProvider.GROQ.name)
    val GROQ_MODEL = SettingsKey(stringPreferencesKey("openwispr_groq_model"), OpenWisprConfig.DEFAULT_GROQ_MODEL)
    val OPEN_ROUTER_MODEL = SettingsKey(stringPreferencesKey("openwispr_openrouter_model"), OpenWisprConfig.DEFAULT_OPEN_ROUTER_MODEL)
    val LANGUAGE = SettingsKey(stringPreferencesKey("openwispr_language"), "")
    val REFINEMENT_ENABLED = SettingsKey(booleanPreferencesKey("openwispr_refinement_enabled"), false)
    val REFINEMENT_PROVIDER = SettingsKey(stringPreferencesKey("openwispr_refinement_provider"), OpenWisprProvider.GROQ.name)
    val GROQ_REFINEMENT_MODEL = SettingsKey(stringPreferencesKey("openwispr_groq_refinement_model"), OpenWisprConfig.DEFAULT_GROQ_REFINEMENT_MODEL)
    val OPEN_ROUTER_REFINEMENT_MODEL = SettingsKey(stringPreferencesKey("openwispr_openrouter_refinement_model"), OpenWisprConfig.DEFAULT_OPEN_ROUTER_REFINEMENT_MODEL)
    val REFINEMENT_PROMPT = SettingsKey(stringPreferencesKey("openwispr_refinement_prompt"), OpenWisprConfig.DEFAULT_REFINEMENT_PROMPT)
}

object OpenWisprConfigStore {
    fun load(context: Context): OpenWisprConfig = runBlocking(Dispatchers.IO) {
        loadAsync(context)
    }

    private suspend fun loadAsync(context: Context): OpenWisprConfig {
        val preferences = context.dataStore.data.first()
        val secrets = OpenWisprSecretStore.read(context)
        fun <T> read(key: SettingsKey<T>): T = preferences[key.key] ?: key.default
        val (openRouterModel, openRouterRefinementModel, refinementPrompt) =
            OpenWisprConfig.migrateLegacyDefaults(
                read(OpenWisprSettingKeys.OPEN_ROUTER_MODEL),
                read(OpenWisprSettingKeys.OPEN_ROUTER_REFINEMENT_MODEL),
                read(OpenWisprSettingKeys.REFINEMENT_PROMPT),
            )
        return OpenWisprConfig(
            provider = enumValue(read(OpenWisprSettingKeys.PROVIDER), OpenWisprProvider.GROQ),
            groqApiKey = secrets.groqApiKey,
            openRouterApiKey = secrets.openRouterApiKey,
            groqModel = read(OpenWisprSettingKeys.GROQ_MODEL),
            openRouterModel = openRouterModel,
            language = read(OpenWisprSettingKeys.LANGUAGE),
            refinementEnabled = read(OpenWisprSettingKeys.REFINEMENT_ENABLED),
            refinementProvider = enumValue(
                read(OpenWisprSettingKeys.REFINEMENT_PROVIDER),
                OpenWisprProvider.GROQ,
            ),
            groqRefinementModel = read(OpenWisprSettingKeys.GROQ_REFINEMENT_MODEL),
            openRouterRefinementModel = openRouterRefinementModel,
            refinementPrompt = refinementPrompt,
            secureStorageAvailable = secrets.secureStorageAvailable,
        )
    }

    suspend fun save(context: Context, config: OpenWisprConfig) {
        context.setSetting(OpenWisprSettingKeys.PROVIDER, config.provider.name)
        context.setSetting(OpenWisprSettingKeys.GROQ_MODEL, config.groqModel.trim())
        context.setSetting(OpenWisprSettingKeys.OPEN_ROUTER_MODEL, config.openRouterModel.trim())
        context.setSetting(OpenWisprSettingKeys.LANGUAGE, config.language.trim())
        context.setSetting(OpenWisprSettingKeys.REFINEMENT_ENABLED, config.refinementEnabled)
        context.setSetting(OpenWisprSettingKeys.REFINEMENT_PROVIDER, config.refinementProvider.name)
        context.setSetting(OpenWisprSettingKeys.GROQ_REFINEMENT_MODEL, config.groqRefinementModel.trim())
        context.setSetting(OpenWisprSettingKeys.OPEN_ROUTER_REFINEMENT_MODEL, config.openRouterRefinementModel.trim())
        context.setSetting(OpenWisprSettingKeys.REFINEMENT_PROMPT, config.refinementPrompt.trim())
        OpenWisprSecretStore.write(context, config.groqApiKey.trim(), config.openRouterApiKey.trim())
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}

private data class SecretSnapshot(
    val groqApiKey: String,
    val openRouterApiKey: String,
    val secureStorageAvailable: Boolean,
)

private object OpenWisprSecretStore {
    private val box = SecretBox("openwispr_provider_keys")
    @Volatile private var volatileGroqKey = ""
    @Volatile private var volatileOpenRouterKey = ""

    private fun preferences(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): SecretSnapshot = try {
        migrateLegacy(context)
        val preferences = preferences(context)
        fun key(name: String) = preferences.getString(name, null)?.let(box::open).orEmpty()
        SecretSnapshot(key(KEY_GROQ), key(KEY_OPEN_ROUTER), secureStorageAvailable = true)
    } catch (_: Exception) {
        SecretSnapshot(volatileGroqKey, volatileOpenRouterKey, secureStorageAvailable = false)
    }

    fun write(context: Context, groqKey: String, openRouterKey: String) {
        try {
            preferences(context)
                .edit()
                .putString(KEY_GROQ, box.seal(groqKey))
                .putString(KEY_OPEN_ROUTER, box.seal(openRouterKey))
                .apply()
            volatileGroqKey = ""
            volatileOpenRouterKey = ""
        } catch (_: Exception) {
            volatileGroqKey = groqKey
            volatileOpenRouterKey = openRouterKey
        }
    }

    /**
     * Keys saved before 0.1.15 sit in androidx EncryptedSharedPreferences, which is deprecated.
     * Move them across once so updating doesn't sign the user out of their provider.
     */
    @Suppress("DEPRECATION")
    private fun migrateLegacy(context: Context) {
        if (!java.io.File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE_NAME.xml").exists()) return
        val legacy = androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            LEGACY_FILE_NAME,
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        write(context, legacy.getString(KEY_GROQ, "").orEmpty(), legacy.getString(KEY_OPEN_ROUTER, "").orEmpty())
        context.deleteSharedPreferences(LEGACY_FILE_NAME)
    }

    private const val FILE_NAME = "openwispr_provider_keys"
    private const val LEGACY_FILE_NAME = "openwispr_provider_secrets"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_OPEN_ROUTER = "openrouter_api_key"
}
