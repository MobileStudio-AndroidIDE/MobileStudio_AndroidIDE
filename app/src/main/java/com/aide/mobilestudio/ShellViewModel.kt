package com.aide.mobilestudio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val _shellState = MutableLiveData<ShellState>(ShellState.Stopped)
    val shellState: LiveData<ShellState> = _shellState

    private val _outputLines = MutableLiveData<List<String>>(emptyList())
    val outputLines: LiveData<List<String>> = _outputLines

    private val _currentDirectory = MutableLiveData<String>("")
    val currentDirectory: LiveData<String> = _currentDirectory

    private val _lastExitCode = MutableLiveData(0)
    val lastExitCode: LiveData<Int> = _lastExitCode

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val outputBuffer = mutableListOf<String>()

    sealed class ShellState {
        object Stopped : ShellState()
        object Running : ShellState()
        data class Error(val message: String) : ShellState()
    }

    fun startShell() {
        _shellState.value = ShellState.Running
    }

    fun stopShell() {
        _shellState.value = ShellState.Stopped
    }

    fun appendOutput(line: String) {
        outputBuffer.add(line)
        if (outputBuffer.size > 10000) {
            outputBuffer.removeAt(0)
        }
        _outputLines.value = outputBuffer.toList()
    }

    fun clearOutput() {
        outputBuffer.clear()
        _outputLines.value = emptyList()
    }

    fun updateCwd(cwd: String) {
        _currentDirectory.value = cwd
    }

    fun updateExitCode(code: Int) {
        _lastExitCode.value = code
    }

    fun getHistory(persistentShell: PersistentShell?): List<String> {
        return persistentShell?.getHistory() ?: emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
