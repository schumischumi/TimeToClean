package com.example.timetoclean // Your package

import android.graphics.Bitmap
import android.graphics.ImageDecoder.*
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.timetoclean.databinding.FragmentCroppingBinding // Your binding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri

enum class CropStage {
    SELECTING_TITLE,
    SELECTING_INGREDIENTS,
    SELECTING_PREPARATION,
    DONE
}

class CroppingFragment : Fragment() {

    private var _binding: FragmentCroppingBinding? = null
    private val binding get() = _binding!!
    private val args: CroppingFragmentArgs by navArgs()

    // Use the new ViewModel
    private val recipeAreasViewModel: RecipeAreasViewModel by activityViewModels()

    private var originalBitmap: Bitmap? = null
    private val selectionRect = RectF()
    private var startX = 0f
    private var startY = 0f

    private var currentCropStage = CropStage.SELECTING_TITLE

    private var tempTitleFile: File? = null
    private var tempIngredientsFile: File? = null
    private var tempPreparationFile: File? = null
    private var currentImageUri: Uri? = null


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCroppingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            val imageUriString = args.imageUri
            currentImageUri = imageUriString.toUri()
            Log.d("CroppingFragment", "Received image URI: $currentImageUri")

            // Convert the URI to a Bitmap
            currentImageUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(requireContext().contentResolver, uri)
                    originalBitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }

                } else {
                    // For older versions, use MediaStore.Images.Media.getBitmap (deprecated in API 29)
                    // This requires READ_EXTERNAL_STORAGE permission for Uris not from your own app
                    // or if it's not a MediaStore URI that your app has direct access to.
                    // However, Uris from ACTION_GET_CONTENT or ACTION_IMAGE_CAPTURE (via FileProvider)
                    // usually grant temporary access.
                    @Suppress("DEPRECATION")
                    originalBitmap = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
                }
            }

        } catch (e: Exception) {
            Log.e("CroppingFragment", "Error parsing URI or loading Bitmap from URI: ${args.imageUri}", e)
            originalBitmap = null // Ensure bitmap is null on error
        }

        if (originalBitmap != null) {
            binding.imageViewToCrop.setImageBitmap(originalBitmap)
            setupManualSelection()
            updateUiForCurrentStage()
        } else {
            Log.e("CroppingFragment", "Failed to load originalBitmap. Check Assets.getImageBitmap implementation and logs.")
            binding.buttonConfirmCurrentArea.isEnabled = false
            recipeAreasViewModel.setCropError("Failed to load source image. Check logs for details.")
            // You might want to display a more specific error to the user or take other actions
        }

        // Example: Assume you have a button like 'buttonConfirmCurrentArea'
        binding.buttonConfirmCurrentArea.setOnClickListener {
            originalBitmap?.let { bmp ->
                if (selectionRect.width() > 0 && selectionRect.height() > 0) {
                    processCurrentAreaSelection(bmp, selectionRect)
                } else {
                    recipeAreasViewModel.setCropError("Please select an area for ${currentCropStage.name.toLowerCase(Locale.ROOT).replace('_',' ')}.")
                }
            }
        }

        // Example: Assume a button 'buttonDoneAllCropping'
        binding.buttonDoneAllCropping.setOnClickListener {
            if (recipeAreasViewModel.areAllAreasSet()) {
                // Navigate or signal completion
                // findNavController().navigate(R.id.action_croppingFragment_to_processingFragment)
                Log.i("CroppingFragment", "All areas cropped and set in ViewModel.")
            } else {
                recipeAreasViewModel.setCropError("Not all areas have been selected yet.")
            }
            findNavController().navigate(R.id.crop_to_second)
        }
    }

    private fun updateUiForCurrentStage() {
        selectionRect.setEmpty() // Reset selection rectangle for new area
        binding.selectionOverlay.visibility = View.GONE
        binding.buttonConfirmCurrentArea.isEnabled = originalBitmap != null // Enable if image is loaded

        when (currentCropStage) {
            CropStage.SELECTING_TITLE -> {
                binding.textViewCurrentSelectionPrompt.text = "Select Title Area"
                binding.buttonDoneAllCropping.visibility = View.GONE
                binding.buttonConfirmCurrentArea.text = "Confirm Title Area"
            }
            CropStage.SELECTING_INGREDIENTS -> {
                binding.textViewCurrentSelectionPrompt.text = "Select Ingredients Area"
                binding.buttonConfirmCurrentArea.text = "Confirm Ingredients Area"
            }
            CropStage.SELECTING_PREPARATION -> {
                binding.textViewCurrentSelectionPrompt.text = "Select Preparation Area"
                binding.buttonConfirmCurrentArea.text = "Confirm Preparation Area"
            }
            CropStage.DONE -> {
                binding.textViewCurrentSelectionPrompt.text = "All areas selected!"
                binding.buttonConfirmCurrentArea.visibility = View.GONE
                binding.selectionOverlay.visibility = View.GONE
                binding.buttonDoneAllCropping.visibility = View.VISIBLE
                binding.buttonDoneAllCropping.isEnabled = recipeAreasViewModel.areAllAreasSet()
            }
        }
    }

    private fun processCurrentAreaSelection(sourceBitmap: Bitmap, cropRectInViewCoords: RectF) {
        // --- Coordinate Transformation (same as before) ---
        val imageView = binding.imageViewToCrop
        val displayMatrix = Matrix()
        imageView.imageMatrix.invert(displayMatrix)
        val bitmapSpaceRect = RectF(cropRectInViewCoords)
        displayMatrix.mapRect(bitmapSpaceRect)
        // (Clamping logic for bitmapSpaceRect as before)
        bitmapSpaceRect.left = Math.max(0f, bitmapSpaceRect.left)
        bitmapSpaceRect.top = Math.max(0f, bitmapSpaceRect.top)
        bitmapSpaceRect.right = Math.min(sourceBitmap.width.toFloat(), bitmapSpaceRect.right)
        bitmapSpaceRect.bottom = Math.min(sourceBitmap.height.toFloat(), bitmapSpaceRect.bottom)

        if (bitmapSpaceRect.width() <= 0 || bitmapSpaceRect.height() <= 0) {
            recipeAreasViewModel.setCropError("Invalid area selected for ${currentCropStage.name.toLowerCase(Locale.ROOT)}.")
            return
        }

        try {
            val croppedBitmap = Bitmap.createBitmap(
                sourceBitmap,
                bitmapSpaceRect.left.toInt(),
                bitmapSpaceRect.top.toInt(),
                bitmapSpaceRect.width().toInt(),
                bitmapSpaceRect.height().toInt()
            )

            // Save the cropped bitmap to a temporary file
            val areaName = currentCropStage.name.toLowerCase(Locale.ROOT)
            val tempCroppedFile = createTemporaryCroppedFile(areaName)
            var saveSuccessful = false
            try {
                FileOutputStream(tempCroppedFile).use { out ->
                    val success = croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    if (success) {
                        Log.i("CroppingFragment", "Bitmap.compress successful for ${tempCroppedFile.name}")
                        saveSuccessful = true
                    } else {
                        Log.e("CroppingFragment", "Bitmap.compress returned false for ${tempCroppedFile.name}. File might be incomplete or not correctly written.")
                        // tempCroppedFile might exist but be invalid/empty
                    }
                }
            } catch (e: Exception) {
                Log.e("CroppingFragment", "IOException during FileOutputStream for ${tempCroppedFile.name}", e)
                // saveSuccessful remains false
            }

            if (saveSuccessful && tempCroppedFile.exists()) {
                val fileSize = tempCroppedFile.length()
                Log.i("CroppingFragment", "CONFIRMED: File exists at ${tempCroppedFile.absolutePath}, Size: $fileSize bytes")
                if (fileSize == 0L) {
                    Log.e("CroppingFragment", "ERROR: Cropped file for '$areaName' exists BUT IS EMPTY: ${tempCroppedFile.absolutePath}. URI will likely fail.")
                    // Handle error: Do not proceed with this file, maybe show an error to the user.
                    recipeAreasViewModel.setCropError("Failed to save cropped $areaName correctly (empty file).")
                    return // Exit because the file is invalid
                }
            } else {
                Log.e("CroppingFragment", "ERROR: Cropped file for '$areaName' DOES NOT EXIST or save failed at ${tempCroppedFile.absolutePath} after attempting save.")
                // Handle error: Do not proceed to generate a URI.
                recipeAreasViewModel.setCropError("Failed to save cropped $areaName.")
                return // Exit because the file is invalid or wasn't saved
            }
            val authority = "${requireContext().packageName}.fileprovider"
            val croppedFileUri = FileProvider.getUriForFile(
                requireContext(),
                authority,
                tempCroppedFile
            )

            // Update the ViewModel based on the current stage
            when (currentCropStage) {
                CropStage.SELECTING_TITLE -> {
                    recipeAreasViewModel.setTitleUri(croppedFileUri)
                    tempTitleFile = tempCroppedFile // Keep reference if needed for cleanup
                    currentCropStage = CropStage.SELECTING_INGREDIENTS
                }
                CropStage.SELECTING_INGREDIENTS -> {
                    recipeAreasViewModel.setIngredientsUri(croppedFileUri)
                    tempIngredientsFile = tempCroppedFile
                    currentCropStage = CropStage.SELECTING_PREPARATION
                }
                CropStage.SELECTING_PREPARATION -> {
                    recipeAreasViewModel.setPreparationUri(croppedFileUri)
                    tempPreparationFile = tempCroppedFile
                    currentCropStage = CropStage.DONE
                }
                CropStage.DONE -> { /* Should not happen here */ }
            }
            Log.i("CroppingFragment", "Cropped $areaName saved to URI: $croppedFileUri")
            updateUiForCurrentStage() // Update UI for the next stage

        } catch (e: Exception) {
            Log.e("CroppingFragment", "Error cropping/saving for $currentCropStage", e)
            recipeAreasViewModel.setCropError("Failed to crop ${currentCropStage.name.toLowerCase(Locale.ROOT)}: ${e.message}")
        }
    }

    @Throws(IOException::class)
    private fun createTemporaryCroppedFile(areaIdentifier: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = requireContext().cacheDir
        return File.createTempFile("CROP_${areaIdentifier}_${timeStamp}_", ".jpg", storageDir).apply {
            // deleteOnExit() // Consider if you want these to persist briefly or be cleaned up
        }
    }

    // setupManualSelection(), normalizeAndClampRect(), updateOverlayBounds() remain similar
    // ... (ensure they are present and working) ...

// In CroppingFragment.kt

    private fun setupManualSelection() {
        binding.imageViewToCrop.setOnTouchListener { view, event ->
            val viewWidth = view.width
            val viewHeight = view.height
            if (viewWidth == 0 || viewHeight == 0) { // ImageView not laid out yet
                Log.w("CroppingFragment", "ImageView dimensions are zero in onTouch.")
                return@setOnTouchListener false // Or true, depending on if you want to consume
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    selectionRect.set(startX, startY, startX, startY)
                    binding.selectionOverlay.visibility = View.VISIBLE
                    updateOverlayBounds() // Initial tiny overlay at start point
                    Log.d("CroppingFragment", "ACTION_DOWN: ($startX, $startY)")
                    true // Consume the event
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentX = event.x
                    val currentY = event.y
                    normalizeAndClampRect(selectionRect, startX, startY, currentX, currentY, viewWidth, viewHeight)
                    updateOverlayBounds()
                    Log.d("CroppingFragment", "ACTION_MOVE: Rect: $selectionRect")
                    true // Consume the event
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Optional: You might want to do a final updateOverlayBounds()
                    // Check if selectionRect is valid (width > 0, height > 0)
                    if (selectionRect.width() <= 0 || selectionRect.height() <= 0) {
                        Log.d("CroppingFragment", "ACTION_UP: Invalid selection, resetting overlay.")
                        // binding.selectionOverlay.visibility = View.GONE // Or just leave it if it's tiny
                    } else {
                        Log.d("CroppingFragment", "ACTION_UP: Final Selection: $selectionRect")
                    }
                    true // Consume the event
                }
                else -> false
            }
        }
    }

    private fun normalizeAndClampRect(rect: RectF, x1: Float, y1: Float, x2: Float, y2: Float, viewWidth: Int, viewHeight: Int) {
        rect.left = Math.min(x1, x2).coerceIn(0f, viewWidth.toFloat())
        rect.top = Math.min(y1, y2).coerceIn(0f, viewHeight.toFloat())
        rect.right = Math.max(x1, x2).coerceIn(0f, viewWidth.toFloat())
        rect.bottom = Math.max(y1, y2).coerceIn(0f, viewHeight.toFloat())
    }

    private fun updateOverlayBounds() {
        if (selectionRect.isEmpty) { // Or check width/height
            binding.selectionOverlay.visibility = View.GONE
            return
        }
        // Ensure overlay is visible if rect is not empty
        if (binding.selectionOverlay.visibility != View.VISIBLE && (selectionRect.width() > 0 && selectionRect.height() >0)) {
            binding.selectionOverlay.visibility = View.VISIBLE
        }


        val params = binding.selectionOverlay.layoutParams as? ViewGroup.MarginLayoutParams
        if (params == null) {
            Log.e("CroppingFragment", "SelectionOverlay LayoutParams are not MarginLayoutParams")
            return
        }

        params.width = selectionRect.width().toInt()
        params.height = selectionRect.height().toInt()
        params.leftMargin = selectionRect.left.toInt()
        params.topMargin = selectionRect.top.toInt()

        binding.selectionOverlay.layoutParams = params
        // binding.selectionOverlay.requestLayout() // Often implicitly handled by setting layoutParams on some views
        Log.d("CroppingFragment", "Overlay updated: L=${params.leftMargin}, T=${params.topMargin}, W=${params.width}, H=${params.height}")

    }


    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up temporary files if they weren't passed on or if fragment is destroyed mid-process
        _binding = null
    }
}