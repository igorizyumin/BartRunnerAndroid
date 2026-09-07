package com.dougkeen.bart.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Small Java-friendly adapter for lifecycle-scoped Flow collection. */
object LifecycleFlowCollector {
    fun interface Callback<T> {
        fun onValue(value: T)
    }

    @JvmStatic
    fun <T> collect(
        owner: LifecycleOwner,
        flow: Flow<T>,
        callback: Callback<T>,
    ): Job = owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { value -> callback.onValue(value) }
        }
    }
}
