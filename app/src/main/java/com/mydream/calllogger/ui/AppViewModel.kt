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
import com.mydream.calllogger.net.AccountManager
import com.mydream.calllogger.net.FlowApi
import com.mydream.calllogger.net.IngestClient
import com.mydream.calllogger.net.WaStatus
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
    val runningFlow: Boolean = false,
    val partnerId: String? = null
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
            partnerId = account.partnerId
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var callsJob: Job? = null

    fun saveEmail(rawEmail: String) {
        val email = rawEmail.trim()
        settings.email = email
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
        if (!_state.value.hasPermissions) return
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

    /** Manually run the configured flow on a number (a synthetic call). */
    fun runFlow(number: String) {
        val n = number.trim()
        if (n.isBlank() || _state.value.runningFlow) return
        viewModelScope.launch {
            _state.update { it.copy(runningFlow = true) }
            val (_, message) = withContext(Dispatchers.IO) {
                val token = ensureToken()
                    ?: return@withContext false to "Not connected yet — try again in a moment."
                FlowApi.runFlow(token, n, null)
            }
            _state.update { it.copy(runningFlow = false, message = message) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeShare() = _state.update { it.copy(pendingShare = null) }
}
