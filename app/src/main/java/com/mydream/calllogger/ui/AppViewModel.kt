package com.mydream.calllogger.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mydream.calllogger.data.AppDatabase
import com.mydream.calllogger.data.CallEntity
import com.mydream.calllogger.data.CallRepository
import com.mydream.calllogger.export.DateRange
import com.mydream.calllogger.export.Exporter
import com.mydream.calllogger.AppUpdater
import com.mydream.calllogger.BuildConfig
import com.mydream.calllogger.net.AccountManager
import com.mydream.calllogger.net.FlowApi
import com.mydream.calllogger.net.IngestClient
import com.mydream.calllogger.net.SendApi
import com.mydream.calllogger.net.TemplateApi
import com.mydream.calllogger.net.UpdateChecker
import com.mydream.calllogger.net.WaStatus
import com.mydream.calllogger.net.WaTemplate
import org.json.JSONArray
import com.mydream.calllogger.prefs.SettingsManager
import com.mydream.calllogger.work.CallSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareInfo(val uri: Uri, val fileName: String)

/** Which native screen is showing. Flow *editing* still opens the web editor; everything
 *  here (templates + test flow + day-send) is native. */
enum class Route { HOME, TEMPLATES }

data class UiState(
    val onboardingComplete: Boolean,
    val email: String,
    val hasPermissions: Boolean = false,
    val selectedRange: DateRange = DateRange.TODAY,
    val calls: List<CallEntity> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val pendingShare: ShareInfo? = null,
    val waStatus: WaStatus? = null,
    val partnerId: String? = null,
    val active: Boolean = true,
    val update: UpdateChecker.Update? = null,
    val route: Route = Route.HOME,
    val templates: List<WaTemplate> = emptyList(),
    val templatesLoading: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repo = CallRepository(app, db.callDao())
    private val settings = SettingsManager(app)
    private val account = AccountManager(app)
    private val exporter = Exporter(app)

    private val _state = MutableStateFlow(
        UiState(
            onboardingComplete = settings.isOnboardingComplete,
            email = settings.email.orEmpty(),
            partnerId = account.partnerId,
            active = settings.active
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var callsJob: Job? = null

    init {
        checkForUpdate()
    }

    fun saveEmail(rawEmail: String) {
        val email = rawEmail.trim()
        settings.email = email
        // Mark "now" as the flow baseline: only calls from this point on start follow-up
        // flows. Anything already in the device call log is pre-install history and must
        // not message past callers when it's uploaded.
        settings.ensureFlowBaseline(System.currentTimeMillis())
        _state.update { it.copy(onboardingComplete = true, email = email) }
        // Upload any calls captured before onboarding, now that we have an account.
        CallSync.enqueueNow(getApplication())
    }

    fun onPermissionsResult(callLogGranted: Boolean) {
        _state.update { it.copy(hasPermissions = callLogGranted) }
        if (callLogGranted) {
            sync()
            observeSelectedRange()
            loadWaStatus()
        }
    }

    fun selectRange(range: DateRange) {
        _state.update { it.copy(selectedRange = range) }
        if (_state.value.hasPermissions) observeSelectedRange()
    }

    /** Re-reads the device call log and refreshes the visible window. Called on resume. */
    fun refresh() {
        if (!_state.value.hasPermissions) return
        sync()
        observeSelectedRange()
        loadWaStatus()
    }

    private fun observeSelectedRange() {
        callsJob?.cancel()
        val (start, end) = _state.value.selectedRange.bounds()
        callsJob = viewModelScope.launch {
            repo.observeRange(start, end).collect { list ->
                _state.update { it.copy(calls = list) }
            }
        }
    }

    fun sync() {
        if (!_state.value.hasPermissions || !settings.active) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                repo.syncFromDeviceCallLog()
                // Push freshly-synced calls to the backend.
                CallSync.enqueueNow(getApplication())
            } catch (_: Exception) {
                // The call-log permission may have been revoked; ignore and stop loading.
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            val range = _state.value.selectedRange
            val (start, end) = range.bounds()
            val calls = withContext(Dispatchers.IO) { repo.getRange(start, end) }
            if (calls.isEmpty()) {
                _state.update { it.copy(message = "No calls to export for ${range.label}") }
                return@launch
            }
            try {
                val result = exporter.exportToDownloads(range, calls, System.currentTimeMillis())
                _state.update {
                    it.copy(
                        message = "Saved ${result.fileName} to Downloads (${calls.size} calls)",
                        pendingShare = ShareInfo(result.uri, result.fileName)
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Export failed: ${e.message}") }
            }
        }
    }

    /** Get the per-device token, registering one if needed (mirrors CallUploader). */
    private suspend fun ensureToken(): String? = withContext(Dispatchers.IO) {
        var token = account.token
        if (token.isNullOrBlank()) {
            val email = settings.email
            if (email.isNullOrBlank()) return@withContext null
            token = IngestClient.register(email, settings.deviceId)
            if (!token.isNullOrBlank()) account.token = token
        }
        token
    }

    /** Fetch WhatsApp connection / verification / billing status for the header card. */
    fun loadWaStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val token = ensureToken() ?: return@withContext null
                WaStatus.parse(FlowApi.getWaStatus(token))
            }
            if (status != null) {
                status.partnerId?.let { account.partnerId = it }
                _state.update { it.copy(waStatus = status, partnerId = status.partnerId ?: it.partnerId) }
            }
        }
    }

    // --- Native templates / test-flow / day-send ---------------------------------

    fun openTemplates() {
        _state.update { it.copy(route = Route.TEMPLATES) }
        loadTemplates()
    }

    fun closeTemplates() = _state.update { it.copy(route = Route.HOME) }

    /** Fetch the partner's WhatsApp templates for the picker / templates screen. */
    fun loadTemplates() {
        viewModelScope.launch {
            _state.update { it.copy(templatesLoading = true) }
            val list = withContext(Dispatchers.IO) {
                val token = ensureToken() ?: return@withContext null
                TemplateApi.list(token)
            }
            _state.update { it.copy(templates = list ?: emptyList(), templatesLoading = false) }
        }
    }

    /** Submit a template for Meta approval. [onDone] runs on the main thread with the result. */
    fun submitTemplate(
        name: String,
        language: String,
        category: String,
        components: JSONArray,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val token = ensureToken()
                    ?: return@withContext TemplateApi.SubmitResult(false, null, "Not connected yet.")
                TemplateApi.submit(token, name, language, category, components)
            }
            val msg = if (res.ok) "Submitted for approval — status ${res.status}." else (res.error ?: "Submit failed.")
            _state.update { it.copy(message = msg) }
            if (res.ok) loadTemplates()
            onDone(res.ok, msg)
        }
    }

    /** Run the saved flow on [number], simulating a [simType] ('missed'|'incoming') call. */
    fun testFlow(number: String, simType: String, onDone: (ok: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val token = ensureToken()
                    ?: return@withContext FlowApi.RunFlowResult(false, null, "Not connected yet.")
                FlowApi.runFlow(token, number, simType)
            }
            val msg = if (res.ok) "Test flow started for ${res.contact}." else (res.error ?: "Could not start the flow.")
            _state.update { it.copy(message = msg) }
            onDone(res.ok, msg)
        }
    }

    /**
     * Send [template] to the callers in the currently selected range whose call type is in
     * [types] (e.g. incoming + missed). Recipients are de-duplicated by phone number.
     */
    fun sendToRange(
        types: Set<Int>,
        template: String,
        language: String,
        params: List<String>,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            val range = _state.value.selectedRange
            val (start, end) = range.bounds()
            val recipients = withContext(Dispatchers.IO) {
                repo.getRange(start, end)
                    .filter { it.type in types }
                    .mapNotNull { c ->
                        val num = c.e164?.takeIf { it.isNotBlank() }
                            ?: c.number.takeIf { it.any(Char::isDigit) }
                        num?.let { SendApi.Recipient(it, c.name) }
                    }
                    .distinctBy { it.number.filter(Char::isDigit) }
            }
            if (recipients.isEmpty()) {
                val msg = "No matching callers to message for ${range.label}."
                _state.update { it.copy(message = msg) }
                onDone(false, msg)
                return@launch
            }
            val res = withContext(Dispatchers.IO) {
                val token = ensureToken() ?: return@withContext SendApi.Result(false, "Not connected yet.")
                SendApi.sendNow(token, template, language, params, recipients)
            }
            val msg = if (res.ok) "Sending to ${recipients.size} ${range.label.lowercase()} caller(s)…"
                      else (res.error ?: "Send failed.")
            _state.update { it.copy(message = msg) }
            onDone(res.ok, msg)
        }
    }

    /**
     * URL of the web flow editor for this partner, with the device token in the
     * fragment (never sent to the server). Null until we know the partner + token —
     * callers should trigger [loadWaStatus] and retry.
     */
    fun flowEditorUrl(): String? {
        val pid = _state.value.partnerId ?: account.partnerId ?: return null
        val token = account.token ?: return null
        return "https://menuthere.com/flow/$pid#$token"
    }

    /** Master switch — pause/resume call syncing AND the WhatsApp follow-up flow. */
    fun setActive(active: Boolean) {
        settings.active = active
        _state.update { it.copy(active = active) }
        val app = getApplication<Application>()
        if (active) {
            CallSync.schedulePeriodic(app)
            if (_state.value.hasPermissions) sync()
        } else {
            CallSync.cancelAll(app)
        }
        // Enable/disable the flow server-side so it actually stops/starts sending.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val token = ensureToken() ?: return@withContext
                FlowApi.setFlowEnabled(token, active)
            }
        }
    }

    /** Check GitHub Releases for a newer build; shows the update dialog if one exists. */
    fun checkForUpdate() {
        viewModelScope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_CODE) }
            if (update != null) _state.update { it.copy(update = update) }
        }
    }

    /** Download + install the pending update via the system installer. */
    fun installUpdate() {
        val update = _state.value.update ?: return
        AppUpdater.downloadAndInstall(getApplication(), update.apkUrl)
        _state.update { it.copy(update = null) }
    }

    fun dismissUpdate() = _state.update { it.copy(update = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeShare() = _state.update { it.copy(pendingShare = null) }
}
