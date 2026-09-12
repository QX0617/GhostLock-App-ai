package com.ghostlock.app.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostlock.app.ai.AiProvider
import com.ghostlock.app.ai.AiSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AiSettingsRepository(application)

    private val _providers = MutableStateFlow(repo.getProviders())
    val providers: StateFlow<List<AiProvider>> = _providers.asStateFlow()

    private val _activeId = MutableStateFlow(repo.getActiveProvider().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _maxIter = MutableStateFlow(repo.getMaxIterations())
    val maxIter: StateFlow<Int> = _maxIter.asStateFlow()

    fun setMaxIter(v: Int) {
        repo.setMaxIterations(v)
        _maxIter.value = v
    }

    fun refresh() {
        _providers.value = repo.getProviders()
        _activeId.value = repo.getActiveProvider().id
    }

    fun addOrUpdate(provider: AiProvider) {
        repo.addOrUpdate(provider)
        refresh()
    }

    fun delete(id: String) {
        repo.deleteProvider(id)
        refresh()
    }

    fun setActive(id: String) {
        repo.setActiveProvider(id)
        _activeId.value = id
    }
}
