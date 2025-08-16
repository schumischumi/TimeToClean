package com.example.timetoclean

import android.Manifest // For Manifest.permission.POST_NOTIFICATIONS
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import coil.load
import com.example.timetoclean.databinding.FragmentSecondBinding
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ResultFragment : Fragment() {
    private var _binding: FragmentSecondBinding? = null
    private lateinit var extractTextViewModel: ExtractText
    private val recipeAreasViewModel: RecipeAreasViewModel by activityViewModels()
    private var currentCroppedImageUri: Uri? = null
    private var currentRecipeAreas: CropAreas? = null
    private lateinit var timePicker: MaterialTimePicker

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        extractTextViewModel =
            ViewModelProvider(requireActivity())[ExtractText::class.java] // Example
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.textViewProcessingStatus.text = "Waiting for all cropped recipe areas..."

        recipeAreasViewModel.croppedAreas.observe(viewLifecycleOwner) { areas ->
            currentRecipeAreas = areas
            if (areas != null && recipeAreasViewModel.areAllAreasSet()) {
                // Automatically process the cropped image when all areas are set
                currentRecipeAreas?.let { areas ->
                    if (!recipeAreasViewModel.areAllAreasSet()) {
                        binding.textViewProcessingStatus.text = "Not all areas are ready for processing."
                        return@observe
                    }

                    // Clear previous results and build the queue
                    binding.textViewTimeResult.text = ""
                    binding.textViewProcessingStatus.text = "Preparing OCR queue..."

                    ocrProcessingQueue.clear()
                    currentOcrQueueIndex = 0

                    areas.timeUri?.toFileFromContentUri(requireContext())?.let { timeFile ->
                        if (canOpenContentUri(requireContext(), areas.timeUri!!)) { // Check URI access
                            ocrProcessingQueue.add(Pair(timeFile, "time"))
                        } else {
                            Log.e("SecondFragment", "Cannot access Time URI: ${areas.timeUri}")
                            binding.textViewTimeResult.text = "Error: Cannot access Time image file."
                            // ViewModel will also signal this when it tries and fails.
                        }
                    } ?: run {
                        if (areas.timeUri != null) { // Log only if URI existed but conversion failed
                            Log.e("SecondFragment", "Failed to convert Time URI to File: ${areas.timeUri}")
                            binding.textViewTimeResult.text = "Error: Time image file conversion failed."
                        }
                    }
                    if (ocrProcessingQueue.isNotEmpty()) {
                        processNextAreaInQueue()
                    } else {
                        binding.textViewProcessingStatus.text = "No valid image files found to process."
                    }
                } ?: run {
                    Log.w("SecondFragment", "Process button clicked, but no currentRecipeAreas.")
                    binding.textViewProcessingStatus.text = "No recipe areas available to process."
                }
            } else if (areas != null) {
                binding.buttonProcessCropped.isEnabled = false
                var status = "Waiting for: "
                if (areas.timeUri == null) status += "Time"
                binding.textViewProcessingStatus.text = status.trimEnd(',', ' ')
            } else {
                binding.buttonProcessCropped.isEnabled = false
                binding.textViewProcessingStatus.text = "No areas available."
                binding.imageViewTimePreview.setImageDrawable(null)
            }
        }

        recipeAreasViewModel.cropError.observe(viewLifecycleOwner) { error ->
            error?.let {
                binding.textViewProcessingStatus.text = "Cropping Error: $it"
                binding.buttonProcessCropped.isEnabled = false
            }
        }

        if (!extractTextViewModel.isInitialized) {
            // It's good to show some progress while initializing tesseract
            binding.textViewProcessingStatus.text = "Initializing OCR engine..."
            val dataPath = Assets.getTesseractDataParentDir(requireContext())
            extractTextViewModel.initTesseract(dataPath, Config.TESS_LANG, Config.TESS_ENGINE)
            if (extractTextViewModel.isInitialized) {
                binding.textViewProcessingStatus.text = "OCR engine ready. Waiting for areas."
            } else {
                binding.textViewProcessingStatus.text = "OCR engine failed to initialize. Check logs."
                binding.buttonProcessCropped.isEnabled = false // Can't process if not initialized
            }
        }

        // --- OCR ViewModel Observers ---

        extractTextViewModel.ocrTaskCompleted.observe(viewLifecycleOwner) { (areaName, success) ->
            Log.i("SecondFragment", "OCR task completed for '$areaName'. Success: $success. Advancing queue.")
            currentOcrQueueIndex++
            processNextAreaInQueue()
        }

        extractTextViewModel.processing.observe(viewLifecycleOwner) { isProcessing ->
            if (isProcessing) {
                // Potentially show a global progress bar
            } else {
                // Hide global progress bar
            }
        }

        extractTextViewModel.progress.observe(viewLifecycleOwner) { progressText ->
            binding.textViewProcessingStatus.text = progressText
        }

        extractTextViewModel.timeResult.observe(viewLifecycleOwner) { timeText ->
            if (timeText != null) {
                binding.textViewTimeResult.text = timeText.trim().replace("\n", " ")
                Log.i("SecondFragment", "Time OCR Data: $timeText")
                parseTimerStringAndShowPicker(timeText)
            } else {
                Log.i("SecondFragment", "Time OCR Data received null (likely cleared).")
            }
        }

        // Automatically start the timer service when the fragment is loaded
        checkAndStartTimerService()
    }

    fun showPicker(
        hour: Int,
        minute: Int,
    ) {
        val picker =
            MaterialTimePicker
                .Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText("Select Runtime")
                .build()

        picker.show(parentFragmentManager, "TIME_PICKER")

        picker.addOnPositiveButtonClickListener {
            val hours = picker.hour
            val minutes = picker.minute
            val totalMillis = (hours.toLong() * 60L + minutes.toLong()) * 60L * 1000L

            if (totalMillis > 0) {
                checkAndStartTimerService(totalMillis)
            } else {
                Toast.makeText(requireContext(), "Please select a valid duration.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndStartTimerService(timeInMillis: Long = 0L) {
        this.totalMillisForTimer = timeInMillis

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    TimerService.startTimerService(requireContext(), timeInMillis)
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.i("ResultFragment", "Showing rationale for notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            TimerService.startTimerService(requireContext(), timeInMillis)
        }
    }

    fun parseTimerStringAndShowPicker(ocrResult: String) {
        val numbersOnly = ocrResult.replace(":", "")

        var hours: Int? = null
        var minutes: Int? = null

        if (ocrResult.contains(":")) {
            val parts = ocrResult.split(":")
            if (parts.size == 2) {
                val hPart = parts[0].toIntOrNull()
                val mPart = parts[1].toIntOrNull()

                if (hPart != null && mPart != null) {
                    if (hPart in 0..99 && mPart in 0..59) {
                        hours = hPart
                        minutes = mPart
                    }
                }
            }
        }

        when (numbersOnly.length) {
            1 -> { // e.g., "5" -> 00:05
                minutes = numbersOnly.toIntOrNull()
            }
            2 -> { // e.g., "45" -> 00:45
                minutes = numbersOnly.toIntOrNull()
            }
            3 -> { // e.g., "145" -> 01:45
                hours = numbersOnly.substring(0, 1).toIntOrNull()
                minutes = numbersOnly.substring(1).toIntOrNull()
            }
            4 -> { // e.g., "1230" -> 12:30
                hours = numbersOnly.substring(0, 2).toIntOrNull()
                minutes = numbersOnly.substring(2).toIntOrNull()
            }
            5 -> { // e.g., "12345" -> 12:34 (assuming last two are minutes)
                hours = numbersOnly.substring(0, 3).toIntOrNull()
                minutes = numbersOnly.substring(3).toIntOrNull()
                if (minutes == null || minutes !in 0..59) {
                    hours = numbersOnly.substring(0, 2).toIntOrNull()
                    minutes = numbersOnly.substring(2, 4).toIntOrNull()
                }
            }
            6 -> { // e.g., "123456" -> 12:34 (assuming hh:mm:ss, taking hh:mm)
                hours = numbersOnly.substring(0, 2).toIntOrNull()
                minutes = numbersOnly.substring(2, 4).toIntOrNull()
            }
        }

        if (hours != null && minutes != null) {
            if (hours in 0..99 && minutes in 0..59) {
                showPicker(hours, minutes)
                println("Parsed: hh=$hours, mm=$minutes (from $ocrResult)")
                return
            }
        } else if (minutes != null && hours == null) {
            if (minutes in 0..59) {
                showPicker(0, minutes)
                println("Parsed: hh=0, mm=$minutes (from $ocrResult)")
                return
            }
        }

        Log.e("SecondFragment", "Could not reliably parse timer string: $ocrResult")
    }

    private fun processNextAreaInQueue() {
        if (currentOcrQueueIndex < ocrProcessingQueue.size) {
            val (fileToProcess, areaType) = ocrProcessingQueue[currentOcrQueueIndex]
            Log.d("SecondFragment", "Requesting OCR from ViewModel for area: $areaType, File: ${fileToProcess.name}")
            binding.textViewProcessingStatus.text = "Starting OCR for $areaType..."
            extractTextViewModel.recognizeImage(fileToProcess, areaType)
        } else {
            Log.i("SecondFragment", "All areas in OCR queue have been processed.")
            binding.textViewProcessingStatus.text = "All OCR processing finished."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun formatOcrList(rawText: String?): String {
    if (rawText.isNullOrBlank()) {
        return ""
    }

    val processedText = rawText.trim()
    val doubleLineBreak = "\n\n"
    val singleLineBreak = "\n"

    val initialSplitItems: List<String>

    if (processedText.contains(doubleLineBreak)) {
        initialSplitItems = processedText.split(doubleLineBreak)
    } else if (processedText.contains(singleLineBreak)) {
        initialSplitItems = processedText.split(singleLineBreak)
    } else {
        initialSplitItems = listOf(processedText)
    }

    val finalLines =
        initialSplitItems.flatMap { item ->
            listOf(item)
        }

    return finalLines
        .map { it.trim().replace("\n", "") }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n") { item ->
            "# $item"
        }
}

fun Uri.toFileFromContentUri(context: Context): File? {
    if (this.scheme != "content") {
        Log.w("UriToFile", "URI scheme is not 'content': $this. Trying to create File directly if it's a file URI.")
        if (this.scheme == "file") {
            this.path?.let { return File(it) }
        }
        return null
    }

    var file: File? = null
    try {
        val displayName = System.currentTimeMillis().toString()
        val tempFile = File(context.cacheDir, "$displayName.tmp")

        context.contentResolver.openInputStream(this)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
                file = tempFile
            }
        }
    } catch (e: IOException) {
        Log.e("UriToFile", "IOException during content URI to File conversion: $this", e)
    } catch (e: Exception) {
        Log.e("UriToFile", "Exception during content URI to File conversion: $this", e)
    }

    return file
}

fun canOpenContentUri(
    context: Context,
    uri: Uri,
): Boolean {
    Log.d("UriCheck_Detail", "Attempting to open URI: $uri with context: $context")
    if (uri.scheme != "content") {
        Log.w("UriCheck_Detail", "URI scheme is not 'content': $uri")
        return false
    }
    try {
        Log.d("UriCheck_Detail", "Calling context.contentResolver.openInputStream(uri) for: $uri")
        val inputStream = context.contentResolver.openInputStream(uri)
        Log.d("UriCheck_Detail", "Result of openInputStream: ${if (inputStream == null) "NULL" else "NOT NULL"}")

        inputStream?.use {
            Log.i("UriCheck_Detail", "Successfully opened InputStream for $uri. Stream: $it")
            return true
        }
        Log.w("UriCheck_Detail", "InputStream was null for $uri without an exception after check.")
        return false
    } catch (e: java.io.FileNotFoundException) {
        Log.e(
            "UriCheck_Detail",
            "FileNotFoundException for $uri during openInputStream. Path may not exist or permissions issue at resolver level.",
            e,
        )
        val expectedCacheFile = File(context.cacheDir, uri.lastPathSegment ?: "error_no_last_segment")
        Log.e(
            "UriCheck_Detail",
            "DEBUG: Does expected file exist NOW? Path: ${expectedCacheFile.absolutePath}, Exists: ${expectedCacheFile.exists()}, Size: ${expectedCacheFile.length()}",
        )
        return false
    } catch (e: SecurityException) {
        Log.e("UriCheck_Detail", "SecurityException for $uri during openInputStream. Permission denied at resolver level.", e)
        return false
    } catch (e: Exception) {
        Log.e("UriCheck_Detail", "Unexpected exception during openInputStream for $uri.", e)
        return false
    }
}

fun makeWhitespaceVisible(text: String?): String {
    if (text == null) {
        return "null"
    }
    if (text.isEmpty()) {
        return "[EMPTY STRING]"
    }
    return text
        .replace("\n", "[NL]")
        .replace("\r", "[CR]")
        .replace("\t", "[TAB]")
}
