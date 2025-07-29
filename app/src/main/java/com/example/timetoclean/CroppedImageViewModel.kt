package com.example.timetoclean

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel


data class CropAreas(
    val titleUri: Uri? = null,
    val ingredientsUri: Uri? = null,
    val preparationUri: Uri? = null
)

class RecipeAreasViewModel : ViewModel() { // Renamed for clarity

    private val _croppedAreas = MutableLiveData<CropAreas?>()
    val croppedAreas: LiveData<CropAreas?> = _croppedAreas

    private val _cropError = MutableLiveData<String?>()
    val cropError: LiveData<String?> = _cropError

    // --- Methods to set individual areas ---
    fun setTitleUri(uri: Uri?) {
        val currentAreas = _croppedAreas.value ?: CropAreas()
        _croppedAreas.value = currentAreas.copy(titleUri = uri)
        if (uri == null) _cropError.value = null // Clear general error if a part is cleared
    }

    fun setIngredientsUri(uri: Uri?) {
        val currentAreas = _croppedAreas.value ?: CropAreas()
        _croppedAreas.value = currentAreas.copy(ingredientsUri = uri)
        if (uri == null) _cropError.value = null
    }

    fun setPreparationUri(uri: Uri?) {
        val currentAreas = _croppedAreas.value ?: CropAreas()
        _croppedAreas.value = currentAreas.copy(preparationUri = uri)
        if (uri == null) _cropError.value = null
    }

    fun setCropError(errorMessage: String?) {
        _cropError.value = errorMessage
    }

    fun areAllAreasSet(): Boolean {
        return _croppedAreas.value?.let {
            it.titleUri != null && it.ingredientsUri != null && it.preparationUri != null
        } ?: false
    }
}