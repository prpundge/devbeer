package com.devutility.ai.settings

import com.devutility.ai.model.ProviderKind
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persisted AI configuration.
 *
 * The API key is deliberately **not** a field here. Anything in this class is serialised to
 * `devbeer-ai.xml` in plain text; credentials go to [AiCredentials] and the OS keychain
 * instead (§5, §21, Constitution §V).
 */
@Service(Service.Level.APP)
@State(name = "DevBeerAiSettings", storages = [Storage("devbeer-ai.xml")])
class DevBeerAiSettings : PersistentStateComponent<DevBeerAiSettings> {

    /** Off until the user turns it on: the plugin must never make an unexpected call (§21). */
    var enabled: Boolean = false

    var provider: ProviderKind = ProviderKind.LOCAL

    var localEndpoint: String = DEFAULT_LOCAL_ENDPOINT
    var localModel: String = ""

    var openAiEndpoint: String = DEFAULT_OPENAI_ENDPOINT
    var openAiModel: String = ""

    var timeoutSeconds: Int = 60

    // ---- context budget (§8) ----
    var maxFiles: Int = 10
    var maxContextCharacters: Int = 50_000
    var maxMethods: Int = 30
    var maxStackTraceLines: Int = 100

    override fun getState(): DevBeerAiSettings = this

    override fun loadState(state: DevBeerAiSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    val endpoint: String
        get() = when (provider) {
            ProviderKind.LOCAL -> localEndpoint
            ProviderKind.OPENAI_COMPATIBLE -> openAiEndpoint
        }.trim().trimEnd('/')

    val model: String
        get() = when (provider) {
            ProviderKind.LOCAL -> localModel
            ProviderKind.OPENAI_COMPATIBLE -> openAiModel
        }.trim()

    /**
     * Configured means: switched on, with an endpoint and a model. The key is only required
     * for the OpenAI-compatible provider, and even there some gateways do not use one.
     */
    fun isConfigured(): Boolean = enabled && endpoint.isNotEmpty() && model.isNotEmpty()

    fun budget(): ContextBudget = ContextBudget(
        maxFiles = maxFiles.coerceAtLeast(1),
        maxCharacters = maxContextCharacters.coerceAtLeast(1_000),
        maxMethods = maxMethods.coerceAtLeast(1),
        maxStackTraceLines = maxStackTraceLines.coerceAtLeast(10),
    )

    companion object {
        const val DEFAULT_LOCAL_ENDPOINT = "http://localhost:11434"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1"

        fun getInstance(): DevBeerAiSettings =
            ApplicationManager.getApplication().getService(DevBeerAiSettings::class.java)
    }
}

/**
 * Hard ceilings on what may be gathered for one request.
 *
 * These exist so repository size never determines request size: a ten-file project and a
 * hundred-thousand-file project produce the same order of payload (§8).
 */
data class ContextBudget(
    val maxFiles: Int = 10,
    val maxCharacters: Int = 50_000,
    val maxMethods: Int = 30,
    val maxStackTraceLines: Int = 100,
)

/**
 * API keys, in the OS keychain via IntelliJ's PasswordSafe — never in settings XML, never in
 * the project, never in a log.
 */
object AiCredentials {

    private const val SUBSYSTEM = "DevBeer AI"

    /**
     * The Plugin Verifier reports this constructor as deprecated on 2024.3, and that is
     * accepted deliberately.
     *
     * Passing the arguments explicitly rather than relying on Kotlin defaults was tried and
     * makes no difference — the constructor itself is deprecated on 243, not just the
     * synthetic default-args bridge. Binding to whatever replaced it would risk a
     * NoSuchMethodError on the 2023.3 floor, which is exactly the failure this plugin already
     * shipped once (see spec v2.1.0, DEF-2.1-1). A warning on the newest IDE is strictly
     * better than a crash on the oldest.
     *
     * Revisit when pluginSinceBuild moves past 233.
     */
    private fun attributes(key: String) = CredentialAttributes(generateServiceName(SUBSYSTEM, key))

    fun apiKey(): String? =
        PasswordSafe.instance.getPassword(attributes(API_KEY))?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String?) {
        PasswordSafe.instance.setPassword(attributes(API_KEY), value?.takeIf { it.isNotBlank() })
    }

    fun hasApiKey(): Boolean = apiKey() != null

    private const val API_KEY = "apiKey"
}
