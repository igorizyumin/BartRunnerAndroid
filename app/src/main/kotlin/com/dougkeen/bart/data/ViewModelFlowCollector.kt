package com.dougkeen.bart.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Java-friendly access to a ViewModel-owned coroutine collection. */
object ViewModelFlowCollector {
    fun interface Callback<T> {
        fun onValue(value: T)
    }

    @JvmStatic
    fun <T> collect(
        viewModel: ViewModel,
        flow: Flow<T>,
        callback: Callback<T>,
    ): Job = viewModel.viewModelScope.launch {
        flow.collect { value -> callback.onValue(value) }
    }
}
