package com.devutility.ai.settings

import com.devutility.ai.llm.OllamaLlmProvider
import com.devutility.ai.model.ProviderKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → DevBeer AI Assistant.
 *
 * The API key field is bound to PasswordSafe, never to the persisted state, so it is not
 * written to `devbeer-ai.xml` (§5, §21).
 */
class DevBeerAiConfigurable : Configurable {

    private val enabled = JBCheckBox("Enable AI Assistant")
    private val providerCombo = ComboBox(DefaultComboBoxModel(ProviderKind.values()))
    private val endpoint = JBTextField()
    private val model = JBTextField()
    private val apiKey = JBPasswordField()
    private val timeout = JBTextField()
    private val maxFiles = JBTextField()
    private val maxCharacters = JBTextField()
    private val status = JBLabel(" ")

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "DevBeer AI Assistant"

    override fun createComponent(): JComponent {
        listOf(endpoint, model, timeout, maxFiles, maxCharacters).forEach {
            it.preferredSize = Dimension(JBUI.scale(320), it.preferredSize.height)
        }
        apiKey.preferredSize = Dimension(JBUI.scale(320), apiKey.preferredSize.height)

        providerCombo.addActionListener { syncProviderFields() }

        val test = JButton("Test connection")
        test.addActionListener { testConnection() }

        val form = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Endpoint:", endpoint)
            .addLabeledComponent("Model:", model)
            .addLabeledComponent("API key:", apiKey)
            .addComponentToRightColumn(
                JBLabel("Stored in the OS keychain, never in project files.").also { dim(it) },
            )
            .addLabeledComponent("Request timeout (s):", timeout)
            .addLabeledComponent("Maximum files per request:", maxFiles)
            .addLabeledComponent("Maximum context characters:", maxCharacters)
            .addComponent(test)
            .addComponent(status)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(10)
        root.add(form, BorderLayout.CENTER)
        panel = root
        reset()
        return root
    }

    private fun dim(label: JBLabel) {
        label.foreground = com.intellij.ui.JBColor.GRAY
        label.font = label.font.deriveFont(label.font.size2D - 1f)
    }

    private fun currentProvider(): ProviderKind =
        providerCombo.selectedItem as? ProviderKind ?: ProviderKind.LOCAL

    private fun syncProviderFields() {
        val local = currentProvider() == ProviderKind.LOCAL
        apiKey.isEnabled = !local
        if (endpoint.text.isBlank()) {
            endpoint.text = if (local) {
                DevBeerAiSettings.DEFAULT_LOCAL_ENDPOINT
            } else {
                DevBeerAiSettings.DEFAULT_OPENAI_ENDPOINT
            }
        }
    }

    /** Explicit, user-initiated — the only place settings makes a network call (§21). */
    private fun testConnection() {
        val kind = currentProvider()
        val host = endpoint.text.trim().trimEnd('/')
        val name = model.text.trim()
        if (host.isEmpty() || name.isEmpty()) {
            status.text = "Set an endpoint and a model first."
            return
        }
        status.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = when (kind) {
                ProviderKind.LOCAL -> OllamaLlmProvider(host, name).isAvailable()
                // A cloud endpoint is not probed: an unauthenticated request would be a call
                // the user did not ask for, and a wasted one.
                ProviderKind.OPENAI_COMPATIBLE -> null
            }
            ApplicationManager.getApplication().invokeLater {
                status.text = when (reachable) {
                    true -> "Reached $host."
                    false -> "Could not reach $host. Is the server running?"
                    null -> "Endpoint saved. It is contacted when you first use the assistant."
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val s = DevBeerAiSettings.getInstance()
        return enabled.isSelected != s.enabled ||
            currentProvider() != s.provider ||
            endpoint.text.trim() != s.endpoint ||
            model.text.trim() != s.model ||
            timeout.text.trim() != s.timeoutSeconds.toString() ||
            maxFiles.text.trim() != s.maxFiles.toString() ||
            maxCharacters.text.trim() != s.maxContextCharacters.toString() ||
            String(apiKey.password).isNotEmpty()
    }

    override fun apply() {
        val s = DevBeerAiSettings.getInstance()
        s.enabled = enabled.isSelected
        s.provider = currentProvider()

        when (s.provider) {
            ProviderKind.LOCAL -> {
                s.localEndpoint = endpoint.text.trim()
                s.localModel = model.text.trim()
            }
            ProviderKind.OPENAI_COMPATIBLE -> {
                s.openAiEndpoint = endpoint.text.trim()
                s.openAiModel = model.text.trim()
            }
        }

        s.timeoutSeconds = timeout.text.trim().toIntOrNull()?.coerceIn(5, 600) ?: 60
        s.maxFiles = maxFiles.text.trim().toIntOrNull()?.coerceIn(1, 100) ?: 10
        s.maxContextCharacters = maxCharacters.text.trim().toIntOrNull()?.coerceIn(1_000, 500_000) ?: 50_000

        // Only overwrite the stored key when the user typed something; a blank field means
        // "leave it alone", not "delete it".
        val typed = String(apiKey.password)
        if (typed.isNotEmpty()) {
            AiCredentials.setApiKey(typed)
            apiKey.text = ""
        }
    }

    override fun reset() {
        val s = DevBeerAiSettings.getInstance()
        enabled.isSelected = s.enabled
        providerCombo.selectedItem = s.provider
        endpoint.text = s.endpoint
        model.text = s.model
        timeout.text = s.timeoutSeconds.toString()
        maxFiles.text = s.maxFiles.toString()
        maxCharacters.text = s.maxContextCharacters.toString()
        apiKey.text = ""
        status.text = if (AiCredentials.hasApiKey()) "An API key is stored." else " "
        syncProviderFields()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
