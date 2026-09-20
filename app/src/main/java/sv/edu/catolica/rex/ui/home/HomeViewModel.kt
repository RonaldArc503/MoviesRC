package sv.edu.catolica.rex.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import sv.edu.catolica.rex.models.MediaItem
import sv.edu.catolica.rex.models.Section
import sv.edu.catolica.rex.repository.HomeDataResult
import sv.edu.catolica.rex.repository.HomeRepository

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(
        val sections: List<Section>,
        val heroCandidates: List<MediaItem>
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(
    private val repository: HomeRepository = HomeRepository()
) : ViewModel() {

    private val _uiState = MutableLiveData<HomeUiState>()
    val uiState: LiveData<HomeUiState> = _uiState

    private val _enrichmentComplete = MutableLiveData<Boolean>()
    val enrichmentComplete: LiveData<Boolean> = _enrichmentComplete

    private var currentData: HomeDataResult? = null

    fun loadHomeData() {
        // Si ya tenemos datos y no se pide refresco forzado, no volvemos a descargar
        currentData?.let { data ->
            _uiState.value = HomeUiState.Success(data.sections, data.heroCandidates)
            return
        }

        _uiState.value = HomeUiState.Loading

        viewModelScope.launch {
            try {
                val result = repository.fetchHomeData()
                currentData = result
                _uiState.value = HomeUiState.Success(result.sections, result.heroCandidates)

                // Enriquecer en segundo plano
                if (result.allItemsToEnrich.isNotEmpty()) {
                    repository.enrichItems(result.allItemsToEnrich)
                    _enrichmentComplete.value = true
                }
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.localizedMessage ?: "Error desconocido")
            }
        }
    }

    fun refresh() {
        currentData = null
        loadHomeData()
    }
}
