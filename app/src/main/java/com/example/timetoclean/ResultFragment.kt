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

    // Add these properties to your SecondFragment class
    private var ocrProcessingQueue: MutableList<Pair<File, String>> = mutableListOf()
    private var currentOcrQueueIndex = 0
    private var totalMillisForTimer: Long = 0L
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Start the service with the stored time.
                if (totalMillisForTimer > 0L) {
                    TimerService.startTimerService(requireContext(), totalMillisForTimer)
                }
            } else {
                // Explain to the user that the feature is unavailable
                Toast
                    .makeText(
                        requireContext(),
                        "Notification permission denied. Timer cannot show notifications.",
                        Toast.LENGTH_LONG,
                    ).show()
                // Optionally, you could guide them to settings, but be respectful of their choice.
            }
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
                binding.buttonProcessCropped.isEnabled = true
                binding.textViewProcessingStatus.text = "Image is ready. Click to process."
                areas.timeUri?.let { binding.imageViewTimePreview.load(it) }
                Log.i("SecondFragment", "Received all cropped areas: Time: ${areas.timeUri}")
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
            // Consider running initTesseract in a background thread if it's slow,
            // and updating UI on completion. For now, keeping it as is.
            extractTextViewModel.initTesseract(dataPath, Config.TESS_LANG, Config.TESS_ENGINE)
            if (extractTextViewModel.isInitialized) {
                binding.textViewProcessingStatus.text = "OCR engine ready. Waiting for areas."
            } else {
                binding.textViewProcessingStatus.text = "OCR engine failed to initialize. Check logs."
                binding.buttonProcessCropped.isEnabled = false // Can't process if not initialized
            }
        }

        binding.buttonProcessCropped.setOnClickListener {
            currentRecipeAreas?.let { areas ->
                if (!recipeAreasViewModel.areAllAreasSet()) {
                    binding.textViewProcessingStatus.text = "Not all areas are ready for processing."
                    return@setOnClickListener
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
                    // Changed to 'run' for statements if the 'let' was null
                    if (areas.timeUri != null) { // Log only if URI existed but conversion failed
                        Log.e("SecondFragment", "Failed to convert Time URI to File: ${areas.timeUri}")
                        binding.textViewTimeResult.text = "Error: Time image file conversion failed."
                    }
                }
                if (ocrProcessingQueue.isNotEmpty()) {
                    binding.buttonProcessCropped.isEnabled = false // Disable button while processing
                    processNextAreaInQueue()
                } else {
                    binding.textViewProcessingStatus.text = "No valid image files found to process."
                }
            } ?: run {
                Log.w("SecondFragment", "Process button clicked, but no currentRecipeAreas.")
                binding.textViewProcessingStatus.text = "No recipe areas available to process."
            }
        }

        binding.buttonPicker.setOnClickListener {
            showPicker(0, 0)
        }

        // --- OCR ViewModel Observers ---

        // NEW Observer for task completion, drives the queue
        extractTextViewModel.ocrTaskCompleted.observe(viewLifecycleOwner) { (areaName, success) ->
            Log.i("SecondFragment", "OCR task completed for '$areaName'. Success: $success. Advancing queue.")
            // Optionally update a more general status based on success/failure of 'areaName'
            // e.g., binding.textViewProcessingStatus.append("\nFinished $areaName (Success: $success)")

            currentOcrQueueIndex++
            processNextAreaInQueue()
        }

        extractTextViewModel.processing.observe(viewLifecycleOwner) { isProcessing ->
            // This is the overall 'processing' state from ViewModel.
            // You might use this to show a general spinner or disable the button.
            // binding.buttonProcessCropped.isEnabled = !isProcessing // Example
            if (isProcessing) {
                // Potentially show a global progress bar
            } else {
                // Hide global progress bar
            }
        }

        extractTextViewModel.progress.observe(viewLifecycleOwner) { progressText ->
            // This updates with detailed progress from Tesseract (init, %, completion time)
            binding.textViewProcessingStatus.text = progressText
        }

        extractTextViewModel.timeResult.observe(viewLifecycleOwner) { timeText ->
            if (timeText != null) {
                binding.textViewTimeResult.text = timeText.trim().replace("\n", " ") // Display the full string (text or error message)
                Log.i("SecondFragment", "Time OCR Data: $timeText")
                parseTimerStringAndShowPicker(timeText)
            } else {
                // If null means it was cleared before processing this area
                Log.i("SecondFragment", "Time OCR Data received null (likely cleared).")
            }
        }
    } // End of onViewCreated

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

//        picker.addOnPositiveButtonClickListener {
//            val selected = String.format("%02d:%02d", picker.hour, picker.minute)
//            setNativeTimer(requireContext(), picker.hour, picker.minute, "TimeToClean Timer")
//        }
        picker.addOnPositiveButtonClickListener {
            val hours = picker.hour
            val minutes = picker.minute
            val totalMillis = (hours.toLong() * 60L + minutes.toLong()) * 60L * 1000L
            // TimerService.startTimerService(requireContext(), totalMillis)
            val calculatedTotalMillis = (hours.toLong() * 60L + minutes.toLong()) * 60L * 1000L

            if (calculatedTotalMillis > 0) {
                // Call your new method to handle permission check and service start
                checkAndStartTimerService(calculatedTotalMillis)
            } else {
                Toast.makeText(requireContext(), "Please select a valid duration.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndStartTimerService(timeInMillis: Long) {
        this.totalMillisForTimer = timeInMillis // Store the time in case we need to request permission

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                    TimerService.startTimerService(requireContext(), timeInMillis)
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Explain to the user why you need the permission.
                    // This is a good place to show a dialog or a Snackbar.
                    // For this example, we'll just log and then request.
                    Log.i("ResultFragment", "Showing rationale for notification permission.")
                    // You should show a proper UI explanation here before launching.
                    // For example, a MaterialAlertDialog:
                    // MaterialAlertDialogBuilder(requireContext())
                    //    .setTitle("Permission Needed")
                    //    .setMessage("This app needs notification permission to show timer progress and completion alerts.")
                    //    .setPositiveButton("OK") { _, _ ->
                    //        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    //    }
                    //    .setNegativeButton("Cancel", null)
                    //    .show()
                    // For simplicity in this direct answer, launching directly:
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // Directly ask for the permission (first time or if rationale not needed)
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Pre-Android 13, no runtime permission needed for notifications
            TimerService.startTimerService(requireContext(), timeInMillis)
        }
    }

    fun parseTimerStringAndShowPicker(ocrResult: String) {
        // Remove all colons to work with just the numbers first
        val numbersOnly = ocrResult.replace(":", "")

        var hours: Int? = null
        var minutes: Int? = null

        // 1. Try direct "hh:mm" match (or "h:mm", "hh:m", "h:m")
        if (ocrResult.contains(":")) {
            val parts = ocrResult.split(":")
            if (parts.size == 2) {
                val hPart = parts[0].toIntOrNull()
                val mPart = parts[1].toIntOrNull()

                if (hPart != null && mPart != null) {
                    // Basic validation for typical timer values
                    if (hPart in 0..99 && mPart in 0..59) { // Allow hours > 23 for timers
                        hours = hPart
                        minutes = mPart
                    }
                }
            }
        }

        // 2. If direct match failed or wasn't attempted, try guessing based on length
        if (hours == null || minutes == null) {
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
                    // or could be "123:45" -> 1:23 (less likely for timers if : is missing)
                    // Prioritizing hh:mm structure
                    hours = numbersOnly.substring(0, 3).toIntOrNull() // Could be too large, validated later
                    minutes = numbersOnly.substring(3).toIntOrNull()
                    // If the above results in invalid minutes, try another split
                    if (minutes == null || minutes !in 0..59) {
                        hours = numbersOnly.substring(0, 2).toIntOrNull()
                        minutes = numbersOnly.substring(2, 4).toIntOrNull() // This is a bit ambiguous with 5 digits
                        // For "12345", this would be 12:34
                    }
                }
                6 -> { // e.g., "123456" -> 12:34 (assuming hh:mm:ss, taking hh:mm)
                    hours = numbersOnly.substring(0, 2).toIntOrNull()
                    minutes = numbersOnly.substring(2, 4).toIntOrNull()
                }
                // Add more cases if you expect longer strings like "1234567" (h:mm:ss.S)
                // or if hours can exceed 2 digits significantly.
            }
        }

        // Validate and call showPicker
        if (hours != null && minutes != null) {
            // Final validation, especially for guessed values
            if (hours in 0..99 && minutes in 0..59) { // Allow hours > 23 for timers
                showPicker(hours, minutes)
                println("Parsed: hh=$hours, mm=$minutes (from $ocrResult)") // For debugging
                return
            }
        } else if (minutes != null && hours == null) { // Case where only minutes were parsed (e.g. "45")
            if (minutes in 0..59) {
                showPicker(0, minutes) // Assume 0 hours
                println("Parsed: hh=0, mm=$minutes (from $ocrResult)") // For debugging
                return
            }
        }

        // Fallback or error handling if no valid interpretation was found
        Log.e("SecondFragment", "Could not reliably parse timer string: $ocrResult")
        // Optionally, call showPicker with default values or show an error
        // showPicker(0, 0) // Example fallback
    }

    // Add this new function to your SecondFragment class
    private fun processNextAreaInQueue() {
        if (currentOcrQueueIndex < ocrProcessingQueue.size) {
            val (fileToProcess, areaType) = ocrProcessingQueue[currentOcrQueueIndex]
            Log.d("SecondFragment", "Requesting OCR from ViewModel for area: $areaType, File: ${fileToProcess.name}")
            binding.textViewProcessingStatus.text = "Starting OCR for $areaType..." // Update status
            extractTextViewModel.recognizeImage(fileToProcess, areaType)
        } else {
            Log.i("SecondFragment", "All areas in OCR queue have been processed.")
            binding.textViewProcessingStatus.text = "All OCR processing finished."
            binding.buttonProcessCropped.isEnabled = true // Re-enable button after all are done
            // Clear queue for next run if desired, or it will be cleared on next button click
            // ocrProcessingQueue.clear()
            // currentOcrQueueIndex = 0
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// In ResultFragment.kt
private fun formatOcrList(rawText: String?): String {
    if (rawText.isNullOrBlank()) {
        return ""
    }

    val processedText = rawText.trim()
    val doubleLineBreak = "\n\n"
    val singleLineBreak = "\n"

    val initialSplitItems: List<String>

    // Prioritize double line breaks for the initial split
    if (processedText.contains(doubleLineBreak)) {
        initialSplitItems = processedText.split(doubleLineBreak)
    } else if (processedText.contains(singleLineBreak)) {
        // Only use single line breaks if no double line breaks are present
        initialSplitItems = processedText.split(singleLineBreak)
    } else {
        // If no line breaks, treat the whole text as a single item
        initialSplitItems = listOf(processedText)
    }

    // This step is important: If we split by double line breaks,
    // an individual item from that split might still contain single line breaks
    // that we also want to treat as delimiters for separate list items.
    // Example: "Item A\nItem B\n\nItem C"
    // Initial split by "\n\n" -> ["Item A\nItem B", "Item C"]
    // We then want to split "Item A\nItem B" further.
    val finalLines =
        initialSplitItems.flatMap { item ->
            listOf(item)
        }

    return finalLines
        .map { it.trim().replace("\n", "") } // Trim each potential line
        .filter { it.isNotBlank() } // Remove any blank lines resulting from splits or trimming
        .joinToString(separator = "\n") { item ->
            // Prepend "# " only to non-blank items
            "# $item"
        }
}

fun Uri.toFileFromContentUri(context: Context): File? {
    if (this.scheme != "content") {
        Log.w("UriToFile", "URI scheme is not 'content': $this. Trying to create File directly if it's a file URI.")
        // If it's already a file URI, try to make a file object from path
        if (this.scheme == "file") {
            this.path?.let { return File(it) }
        }
        return null
    }

    var file: File? = null
    try {
        // Attempt to get the display name to use for the temp file, fallback if null
        val displayName = System.currentTimeMillis().toString() // Fallback name
        val tempFile = File(context.cacheDir, "$displayName.tmp") // Create in cache

        context.contentResolver.openInputStream(this)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
                file = tempFile // Assign file only if copy is successful
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
        val inputStream = context.contentResolver.openInputStream(uri) // THE CRITICAL CALL
        Log.d("UriCheck_Detail", "Result of openInputStream: ${if (inputStream == null) "NULL" else "NOT NULL"}")

        inputStream?.use {
            Log.i("UriCheck_Detail", "Successfully opened InputStream for $uri. Stream: $it")
            // You could even try to read a byte here to be absolutely sure
            // val firstByte = it.read()
            // Log.i("UriCheck_Detail", "First byte read: $firstByte (or -1 if empty/EOF)")
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
        // AT THIS POINT, also try to manually reconstruct and check the File path
        // This is ONLY for debugging if FileProvider is behaving unexpectedly
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

/**
 * Makes common whitespace characters visible in a string for debugging.
 * Replaces \n with "[NL]", \r with "[CR]", and \t with "[TAB]".
 */
fun makeWhitespaceVisible(text: String?): String {
    if (text == null) {
        return "null"
    }
    if (text.isEmpty()) {
        return "[EMPTY STRING]"
    }
    return text
        .replace("\n", "[NL]") // Show [NL] and keep the newline for visual structure
        .replace("\r", "[CR]") // Carriage return is usually consumed or part of \n
        .replace("\t", "[TAB]")
}
