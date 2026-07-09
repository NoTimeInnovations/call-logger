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
import com.mydream.calllogger.prefs.SettingsManager
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
    val pendingShare: ShareInfo? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repo = CallRepository(app, db.callDao())
    private val settings = SettingsManager(app)
    private val exporter = Exporter(app)

    private val _state = MutableStateFlow(
        UiState(
            onboardingComplete = settings.isOnboardingComplete,
            email = settings.email.orEmpty()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var callsJob: Job? = null

    fun saveEmail(rawEmail: String) {
        val email = rawEmail.trim()
        settings.email = email
        _state.update { it.copy(onboardingComplete = true, email = email) }
    }

    fun onPermissionsResult(callLogGranted: Boolean) {
        _state.update { it.copy(hasPermissions = callLogGranted) }
        if (callLogGranted) {
            sync()
            observeSelectedRange()
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

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeShare() = _state.update { it.copy(pendingShare = null) }
}
