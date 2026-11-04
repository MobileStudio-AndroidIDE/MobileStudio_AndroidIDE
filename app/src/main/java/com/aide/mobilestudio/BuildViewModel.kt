package com.aide.mobilestudio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

class BuildViewModel(application: Application) : AndroidViewModel(application) {

    private val _buildState = MutableLiveData<BuildState>(BuildState.Idle)
    val buildState: LiveData<BuildState> = _buildState

    private val _buildProgress = MutableLiveData(0)
    val buildProgress: LiveData<Int> = _buildProgress

    private val _problems = MutableLiveData<List<BuildProblem>>(emptyList())
    val problems: LiveData<List<BuildProblem>> = _problems

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    sealed class BuildState {
        object Idle : BuildState()
        data class Building(val tasks: List<String>) : BuildState()
        data class Success(val durationMs: Long) : BuildState()
        data class Failed(val errors: List<String>, val durationMs: Long) : BuildState()
        object Cancelled : BuildState()
    }

    fun startBuild(tasks: List<String>) {
        _buildState.value = BuildState.Building(tasks)
        _buildProgress.value = 0
    }

    fun updateProgress(progress: Int) {
        _buildProgress.value = progress
    }

    fun addProblem(problem: BuildProblem) {
        val current = _problems.value.orEmpty().toMutableList()
        current.add(problem)
        _problems.value = current
    }

    fun setProblems(problems: List<BuildProblem>) {
        _problems.value = problems
    }

    fun finishBuild(success: Boolean, durationMs: Long, errors: List<String> = emptyList()) {
        _buildState.value = if (success) BuildState.Success(durationMs)
                           else BuildState.Failed(errors, durationMs)
        _buildProgress.value = if (success) 100 else 0
    }

    fun cancelBuild() {
        _buildState.value = BuildState.Cancelled
    }

    fun clearProblems() {
        _problems.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
