package com.animestemsplitter.data

import com.animestemsplitter.engine.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface JobState {
    data object Idle : JobState
    data class Running(val fileName: String, val stage: Stage, val fraction: Float, val etaSec: Int?) : JobState
    data class Done(val result: StemResult) : JobState
    data class Failed(val fileName: String, val message: String) : JobState
}

/** 分離処理（サービス）と画面のあいだで状態を共有する */
object JobRepository {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state
    @Volatile var cancelRequested = false

    fun update(s: JobState) { _state.value = s }
    fun reset() { if (_state.value !is JobState.Running) _state.value = JobState.Idle }
}
