package com.example.timetoclean // Your package name

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import coil.load // Using Coil for image loading, ensure it's in your dependencies
import com.example.timetoclean.databinding.FragmentFirstBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageSelectionFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var currentPhotoUri: Uri? = null
    private var tempImageFileForCamera: File? = null

    private lateinit var requestCameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var selectImageLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assets.extractAssets(requireContext())

        requestCameraPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { isGranted: Boolean ->
                if (isGranted) {
                    Log.d("ImageSelectionFragment", "Camera permission granted")
                    openCamera()
                } else {
                    Log.d("ImageSelectionFragment", "Camera permission denied")
                    Toast.makeText(requireContext(), "Camera permission is required to take photos.", Toast.LENGTH_SHORT).show()
                }
            }

        // Initialize Take Picture Launcher
        takePictureLauncher =
            registerForActivityResult(
                ActivityResultContracts.TakePicture(),
            ) { success: Boolean ->
                if (success) {
                    Log.d(
                        "ImageSelectionFragment",
                        "Image captured successfully. URI: $currentPhotoUri, File: ${tempImageFileForCamera?.absolutePath}",
                    )
                    currentPhotoUri = Uri.fromFile(tempImageFileForCamera) // Ensure currentPhotoUri is set from the file
                    binding.imageViewPreview.load(currentPhotoUri) {
                        placeholder(R.drawable.ic_launcher_background) // Optional placeholder
                        error(com.google.android.material.R.drawable.mtrl_ic_error) // Optional error drawable
                    }
                    binding.buttonNext.isEnabled = true
                } else {
                    Log.d("ImageSelectionFragment", "Image capture failed or was cancelled.")
                    // tempImageFileForCamera might still exist, consider deleting if not needed
                    // tempImageFileForCamera?.delete()
                }
            }

        // Initialize Select Image Launcher
        selectImageLauncher =
            registerForActivityResult(
                ActivityResultContracts.GetContent(),
            ) { uri: Uri? ->
                uri?.let {
                    Log.d("ImageSelectionFragment", "Image selected from storage: $it")
                    currentPhotoUri = it
                    binding.imageViewPreview.load(currentPhotoUri) {
                        placeholder(R.drawable.ic_launcher_background)
                        error(com.google.android.material.R.drawable.mtrl_ic_error)
                    }
                    binding.buttonNext.isEnabled = true
                } ?: Log.d("ImageSelectionFragment", "No image selected from storage.")
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Initially disable next button until an image is loaded
        binding.buttonNext.isEnabled = false

        binding.buttonLoadImage.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        binding.buttonNext.setOnClickListener {
            currentPhotoUri?.let { uri ->
                val action = ImageSelectionFragmentDirections.actionFirstFragmentToCroppingFragment(uri.toString())
                findNavController().navigate(action)
            } ?: Toast.makeText(requireContext(), "Please select an image first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUriForDemoAsset(): Uri? {
        val context = requireContext()
        val assetManager = context.assets
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        val tempFile: File

        try {
            val outputDir = context.cacheDir
            tempFile = File(outputDir, "temp_demo_asset.jpeg")

            inputStream = assetManager.open(Config.DEMO_PIC)
            outputStream = FileOutputStream(tempFile)
            copyFile(inputStream, outputStream)

            val authority = "${context.packageName}.fileprovider"
            return FileProvider.getUriForFile(context, authority, tempFile)
        } catch (e: IOException) {
            Log.e("ImageSelectionFragment", "Error copying demo asset or getting URI: ${e.message}", e)
            return null
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                Log.e("ImageSelectionFragment", "Error closing streams: ${e.message}", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(
        inputStream: InputStream,
        outputStream: OutputStream,
    ) {
        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
    }

    private fun checkCameraPermissionAndOpen() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show an explanation to the user *asynchronously*
                AlertDialog
                    .Builder(requireContext())
                    .setTitle("Camera Permission Needed")
                    .setMessage("This app needs camera access to take pictures for recipe processing.")
                    .setPositiveButton("OK") { _, _ ->
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            }

            else -> {
                // Directly request the permission
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        try {
            tempImageFileForCamera = createImageFile(requireContext())
            tempImageFileForCamera?.let { file ->
                val authority = "${requireContext().packageName}.fileprovider"
                currentPhotoUri =
                    FileProvider.getUriForFile(
                        requireContext(),
                        authority, // Make sure this matches AndroidManifest
                        file,
                    )
                Log.d("ImageSelectionFragment", "FileProvider URI for camera: $currentPhotoUri")
                takePictureLauncher.launch(currentPhotoUri) // Pass the FileProvider URI to the camera
            }
        } catch (ex: IOException) {
            Log.e("ImageSelectionFragment", "Error creating image file for camera", ex)
            Toast.makeText(requireContext(), "Error preparing camera.", Toast.LENGTH_SHORT).show()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(context: Context): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        // Get the directory for storing images. Use app's cache directory.
        val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) // Or context.cacheDir for private cache

        // Ensure the directory exists.
        // For cache, this might not be strictly necessary as it's usually writable,
        // but for getExternalFilesDir it's good practice.
        if (storageDir != null && !storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.e("ImageSelectionFragment", "Failed to create directory for images: ${storageDir.absolutePath}")
                // Fallback to cacheDir if primary external dir fails and is not null
                // Or simply throw IOException if this path is critical
            }
        }
        // Save a file: path for use with ACTION_VIEW intents
        // Use cache dir for temporary files
        val imageFile =
            File.createTempFile(
                imageFileName, // prefix
                ".jpg", // suffix
                storageDir // directory, falls back to cache if storageDir is null or fails
                    ?: context.cacheDir, // Fallback to internal cache if external is unavailable
            )
        Log.d("ImageSelectionFragment", "Image file created at: ${imageFile.absolutePath}")
        return imageFile
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
