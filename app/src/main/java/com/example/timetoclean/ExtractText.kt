package com.example.timetoclean

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale
import kotlin.concurrent.Volatile

class ExtractText(
    application: Application,
) : AndroidViewModel(application) {
    private lateinit var tessApi: TessBaseAPI

    val result = MutableLiveData<String>() // General result - consider its purpose

    var isInitialized: Boolean = false
        private set

    // --- Specific area results ---
    private val _timeResult = MutableLiveData<String?>()
    val timeResult: LiveData<String?> = _timeResult

    // --- Processing State LiveData ---
    private val _processing = MutableLiveData<Boolean>() // Overall processing state of the ViewModel for any area
    val processing: LiveData<Boolean> = _processing

    private val _progress = MutableLiveData<String>()
    val progress: LiveData<String> = _progress

    // --- LiveData to signal completion of an individual OCR task ---
    // Pair<areaType: String, success: Boolean>
    private val _ocrTaskCompleted = MutableLiveData<Pair<String, Boolean>>()
    val ocrTaskCompleted: LiveData<Pair<String, Boolean>> = _ocrTaskCompleted

    @Volatile
    private var stopped = false // For user-initiated stop

    @Volatile
    private var tessProcessing = false // Tracks if Tesseract is busy with an image

    @Volatile
    private var recycleAfterProcessing = false

    private val recycleLock = Any()

    override fun onCleared() {
        super.onCleared()
        synchronized(recycleLock) {
            if (::tessApi.isInitialized) {
                if (tessProcessing) {
                    recycleAfterProcessing = true
                    stopped = true // Ensure any ongoing process tries to stop
                    tessApi.stop()
                    Log.d(TAG, "onCleared: Tess processing, will recycle after current task attempts to stop.")
                } else {
                    tessApi.recycle()
                    Log.d(TAG, "onCleared: Tess recycled immediately.")
                }
            }
        }
    }

    fun initTesseract(
        dataPath: String,
        language: String,
        engineMode: Int,
    ) {
        if (::tessApi.isInitialized && isInitialized) {
            Log.i(TAG, "Tesseract is already initialized.")
            // Optionally re-initialize if parameters change, or just return
            // For now, assume re-init if called again.
        }

        // Ensure not to re-initialize if tessProcessing is true to avoid interrupting ongoing OCR
        if (tessProcessing) {
            Log.w(TAG, "initTesseract called while processing, initialization deferred or skipped.")
            // Maybe queue this or handle as an error
            return
        }

        _progress.postValue("Initializing Tesseract...")
        tessApi =
            TessBaseAPI { progressValues: TessBaseAPI.ProgressValues ->
                _progress.postValue("Progress: " + progressValues.percent + " %")
            }
        Log.i(
            TAG,
            "Initializing Tesseract with: dataPath = [$dataPath], " +
                "language = [$language], engineMode = [$engineMode]",
        )
        try {
            // It's good practice to run init on a background thread if it's potentially long
            // For simplicity here, keeping it synchronous as in original
            isInitialized = tessApi.init(dataPath, language, engineMode)
            if (isInitialized) {
                _progress.postValue(
                    String.format(
                        Locale.ENGLISH,
                        "Tesseract %s (%s) initialized.",
                        tessApi.version,
                        tessApi.libraryFlavor,
                    ),
                )
            } else {
                Log.e(TAG, "Tesseract initialization failed (init returned false).")
                _progress.postValue("Tesseract initialization failed.")
            }
        } catch (e: IllegalArgumentException) {
            isInitialized = false
            Log.e(TAG, "Cannot initialize Tesseract (IllegalArgumentException):", e)
            _progress.postValue("Tesseract init error: ${e.message}")
        } catch (e: Exception) {
            isInitialized = false
            Log.e(TAG, "Unknown error during Tesseract initialization:", e)
            _progress.postValue("Tesseract init error: ${e.message}")
        }
    }

    fun recognizeImage(
        imagePath: File,
        areaType: String,
    ) {
        val areaTypeLower = areaType.toLowerCase(Locale.ROOT)

        if (!isInitialized) {
            Log.e(TAG, "recognizeImage: Tesseract is not initialized for $areaTypeLower")
            postErrorToRelevantLiveData(areaTypeLower, "Tesseract not initialized.")
            _ocrTaskCompleted.postValue(Pair(areaTypeLower, false)) // Signal completion (failure)
            return
        }

        // This check is crucial for sequential processing
        if (tessProcessing) {
            Log.w(TAG, "recognizeImage: Processing is already in progress. Request for $areaTypeLower ignored or queued.")
            postErrorToRelevantLiveData(areaTypeLower, "Another OCR process is active.")
            _ocrTaskCompleted.postValue(Pair(areaTypeLower, false)) // Signal completion (failure due to being busy)
            return
        }

        tessProcessing = true // Mark Tesseract as busy
        _processing.postValue(true) // Update overall processing state
        stopped = false // Reset stopped flag for the new task

        // Clear previous specific result for the current areaType
        when (areaTypeLower) {
            "time" -> _timeResult.postValue(null)
        }
        _progress.postValue("Processing $areaTypeLower...")

        Thread {
            // Tesseract operations should be off the main thread
            var success = false
            try {
                tessApi.setImage(imagePath)
                tessApi.pageSegMode = TessBaseAPI.PageSegMode.PSM_RAW_LINE
                tessApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "01:23456789")
                tessApi.getHOCRText(0)

                val startTime = SystemClock.uptimeMillis()
                val text = if (!stopped) tessApi.utF8Text ?: "" else "OCR stopped by user."

                if (!stopped) {
                    when (areaTypeLower) {
                        "time" -> _timeResult.postValue(text)
                        else -> {
                            Log.w(TAG, "Unrecognized areaType: $areaTypeLower for text: $text")
                            this.result.postValue("For $areaTypeLower (unrecognized): $text")
                        }
                    }
                    val duration = SystemClock.uptimeMillis() - startTime
                    _progress.postValue(
                        String.format(
                            Locale.ENGLISH,
                            "$areaTypeLower: Completed in %.3fs.",
                            (duration / 1000f),
                        ),
                    )
                    success = true
                } else {
                    _progress.postValue("$areaTypeLower: Stopped.")
                    postErrorToRelevantLiveData(areaTypeLower, "OCR stopped by user.")
                    success = false // Not successful if stopped
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during OCR for $areaTypeLower", e)
                postErrorToRelevantLiveData(areaTypeLower, "OCR Error: ${e.message}")
                _progress.postValue("$areaTypeLower: Error.")
                success = false
            } finally {
                if (::tessApi.isInitialized) {
                    tessApi.clear() // Clear Tesseract's internal state for the image.
                }

                synchronized(recycleLock) {
                    tessProcessing = false // Free up Tesseract for the next task
                    _processing.postValue(false) // Update overall processing state

                    if (recycleAfterProcessing) {
                        if (::tessApi.isInitialized) {
                            tessApi.recycle()
                            Log.d(TAG, "Tesseract recycled post-processing for $areaTypeLower (onCleared was pending).")
                            recycleAfterProcessing = false
                            isInitialized = false // Mark as not initialized after recycle
                        }
                    }
                }
                // Signal completion of this specific OCR task
                _ocrTaskCompleted.postValue(Pair(areaTypeLower, success))
            }
        }.start()
    }

    private fun postErrorToRelevantLiveData(
        areaType: String,
        errorMessage: String,
    ) {
        val fullError = "Error for $areaType: $errorMessage"
        // Ensure this runs on the main thread if observers are sensitive
        // _progress.postValue(fullError) // Or a specific error LiveData
        when (areaType.toLowerCase(Locale.ROOT)) {
            "time" -> _timeResult.postValue(fullError)
            else -> this.result.postValue(fullError)
        }
    }

    fun stop() {
        if (!tessProcessing) {
            Log.d(TAG, "Stop called, but not currently processing.")
            return
        }
        _progress.postValue("Stopping current OCR task...")
        stopped = true // Signal the processing thread to stop
        if (::tessApi.isInitialized) {
            tessApi.stop() // Request Tesseract to stop its current operation
        }
    }

    companion object {
        private const val TAG = "ExtractTextVM"
    }
}
