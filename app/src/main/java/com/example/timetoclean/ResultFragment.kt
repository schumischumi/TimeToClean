package com.example.timetoclean

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import coil.load
import com.example.timetoclean.databinding.FragmentSecondBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ResultFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private lateinit var extractTextViewModel: ExtractText
    private val recipeAreasViewModel: RecipeAreasViewModel by activityViewModels()
    private var currentCroppedImageUri: Uri? = null
    private var currentRecipeAreas: CropAreas? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        extractTextViewModel =
            ViewModelProvider(requireActivity())[ExtractText::class.java] // Example
        return binding.root

    }

    // Add these properties to your SecondFragment class
    private var ocrProcessingQueue: MutableList<Pair<File, String>> = mutableListOf()
    private var currentOcrQueueIndex = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        binding.textViewProcessingStatus.text = "Waiting for all cropped recipe areas..."

        recipeAreasViewModel.croppedAreas.observe(viewLifecycleOwner) { areas ->
            currentRecipeAreas = areas
            if (areas != null && recipeAreasViewModel.areAllAreasSet()) {
                binding.buttonProcessCropped.isEnabled = true
                binding.textViewProcessingStatus.text = "All recipe areas ready. Click to process."
                areas.titleUri?.let { binding.imageViewTitlePreview.load(it) }
                areas.ingredientsUri?.let { binding.imageViewIngredientsPreview.load(it) }
                areas.preparationUri?.let { binding.imageViewPreparationPreview.load(it) }
                Log.i("SecondFragment", "Received all cropped areas: Title: ${areas.titleUri}, Ingredients: ${areas.ingredientsUri}, Prep: ${areas.preparationUri}")
            } else if (areas != null) {
                binding.buttonProcessCropped.isEnabled = false
                var status = "Waiting for: "
                if (areas.titleUri == null) status += "Title, "
                if (areas.ingredientsUri == null) status += "Ingredients, "
                if (areas.preparationUri == null) status += "Preparation"
                binding.textViewProcessingStatus.text = status.trimEnd(',', ' ')
            } else {
                binding.buttonProcessCropped.isEnabled = false
                binding.textViewProcessingStatus.text = "No recipe areas available."
                binding.imageViewTitlePreview.setImageDrawable(null)
                binding.imageViewIngredientsPreview.setImageDrawable(null)
                binding.imageViewPreparationPreview.setImageDrawable(null)
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
                binding.textViewTitleResult.text = ""
                binding.textViewIngredientsResult.text = ""
                binding.textViewPreparationResult.text = ""
                binding.textViewProcessingStatus.text = "Preparing OCR queue..."

                ocrProcessingQueue.clear()
                currentOcrQueueIndex = 0

                areas.titleUri?.toFileFromContentUri(requireContext())?.let { titleFile ->
                    if (canOpenContentUri(requireContext(), areas.titleUri!!)) { // Check URI access
                        ocrProcessingQueue.add(Pair(titleFile, "title"))
                    } else {
                        Log.e("SecondFragment", "Cannot access Title URI: ${areas.titleUri}")
                        binding.textViewTitleResult.text = "Error: Cannot access Title image file."
                        // Since we couldn't access it, we can consider this "task" for the title as failed for the queue.
                        // ViewModel will also signal this when it tries and fails.
                    }
                } ?: run { // Changed to 'run' for statements if the 'let' was null
                    if (areas.titleUri != null) { // Log only if URI existed but conversion failed
                        Log.e("SecondFragment", "Failed to convert Title URI to File: ${areas.titleUri}")
                        binding.textViewTitleResult.text = "Error: Title image file conversion failed."
                    }
                    // If areas.titleUri was null in the first place, this block is also skipped by 'let', so no log needed here.
                }


                areas.ingredientsUri?.toFileFromContentUri(requireContext())?.let { ingredientsFile ->
                    if (canOpenContentUri(requireContext(), areas.ingredientsUri!!)) {
                        ocrProcessingQueue.add(Pair(ingredientsFile, "ingredients"))
                    } else {
                        Log.e("SecondFragment", "Cannot access Ingredients URI: ${areas.ingredientsUri}")
                        binding.textViewIngredientsResult.text = "Error: Cannot access Ingredients image file."
                    }
                } ?: run { // Changed to 'run'
                    if (areas.ingredientsUri != null) {
                        Log.e("SecondFragment", "Failed to convert Ingredients URI to File: ${areas.ingredientsUri}")
                        binding.textViewIngredientsResult.text = "Error: Ingredients image file conversion failed."
                    }
                }

                areas.preparationUri?.toFileFromContentUri(requireContext())?.let { preparationFile ->
                    if (canOpenContentUri(requireContext(), areas.preparationUri!!)) {
                        ocrProcessingQueue.add(Pair(preparationFile, "preparation"))
                    } else {
                        Log.e("SecondFragment", "Cannot access Preparation URI: ${areas.preparationUri}")
                        binding.textViewPreparationResult.text = "Error: Cannot access Preparation image file."
                    }
                } ?: run { // Changed to 'run'
                    if (areas.preparationUri != null) {
                        Log.e("SecondFragment", "Failed to convert Preparation URI to File: ${areas.preparationUri}")
                        binding.textViewPreparationResult.text = "Error: Preparation image file conversion failed."
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

        extractTextViewModel.titleResult.observe(viewLifecycleOwner) { titleText ->
            if (titleText != null) {
                binding.textViewTitleResult.text = titleText.trim().replace("\n", " ")  // Display the full string (text or error message)
                Log.i("SecondFragment", "Title OCR Data: $titleText")
            } else {
                // If null means it was cleared before processing this area
                // binding.textViewTitleResult.text = "Processing title..." // Or keep it blank
                Log.i("SecondFragment", "Title OCR Data received null (likely cleared).")
            }
        }

        extractTextViewModel.ingredientsResult.observe(viewLifecycleOwner) { ingredientsText ->
            if (ingredientsText != null) {
                val formattedIngredients = formatOcrList(ingredientsText)
                binding.textViewIngredientsResult.text = formattedIngredients
                Log.i("SecondFragment", "Ingredients OCR Data: $ingredientsText")
            } else {
                Log.i("SecondFragment", "Ingredients OCR Data received null (likely cleared).")
            }
        }

        extractTextViewModel.preparationResult.observe(viewLifecycleOwner) { preparationText ->
            if (preparationText != null) {
                val formattedPreparation = formatOcrList(preparationText)
                binding.textViewPreparationResult.text = formattedPreparation
                Log.i("SecondFragment", "Preparation OCR Data: $preparationText")
            } else {
                Log.i("SecondFragment", "Preparation OCR Data received null (likely cleared).")
            }
        }
    } // End of onViewCreated

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
    val finalLines = initialSplitItems.flatMap { item ->
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
fun canOpenContentUri(context: Context, uri: Uri): Boolean {
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
        Log.e("UriCheck_Detail", "FileNotFoundException for $uri during openInputStream. Path may not exist or permissions issue at resolver level.", e)
        // AT THIS POINT, also try to manually reconstruct and check the File path
        // This is ONLY for debugging if FileProvider is behaving unexpectedly
        val expectedCacheFile = File(context.cacheDir, uri.lastPathSegment ?: "error_no_last_segment")
        Log.e("UriCheck_Detail", "DEBUG: Does expected file exist NOW? Path: ${expectedCacheFile.absolutePath}, Exists: ${expectedCacheFile.exists()}, Size: ${expectedCacheFile.length()}")
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
        .replace("\r", "[CR]")   // Carriage return is usually consumed or part of \n
        .replace("\t", "[TAB]")
}