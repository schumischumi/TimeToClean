package com.example.timetoclean

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class CropAreas(
    val timeUri: Uri? = null,
)

class RecipeAreasViewModel : ViewModel() { // Renamed for clarity

    private val _croppedAreas = MutableLiveData<CropAreas?>()
    val croppedAreas: LiveData<CropAreas?> = _croppedAreas

    private val _cropError = MutableLiveData<String?>()
    val cropError: LiveData<String?> = _cropError

    // --- Methods to set individual areas ---
    fun setTimeUri(uri: Uri?) {
        val currentAreas = _croppedAreas.value ?: CropAreas()
        _croppedAreas.value = currentAreas.copy(timeUri = uri)
        if (uri == null) _cropError.value = null // Clear general error if a part is cleared
    }

    fun setCropError(errorMessage: String?) {
        _cropError.value = errorMessage
    }

    fun areAllAreasSet(): Boolean =
        _croppedAreas.value?.let {
            it.timeUri != null
        } ?: false
}
