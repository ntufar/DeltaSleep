package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.trends.TrendsData
import io.github.ntufar.deltasleep.trends.TrendsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads 90 days of trend data once (D-1). The screen derives 30-day slices
 * for the line/scatter charts; the weekday heatmap uses the full window.
 */
class TrendsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TrendsRepository((app as DeltaSleepApp).database)

    private val _data = MutableStateFlow<TrendsData?>(null)
    val data: StateFlow<TrendsData?> = _data

    init {
        viewModelScope.launch {
            _data.update { repo.load(windowDays = 90) }
        }
    }
}
