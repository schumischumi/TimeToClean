package com.example.timetoclean;

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.io.path.name


object Assets {
    private const val TAG = "AssetsUtil"
    /**
     * Returns locally accessible directory where our assets are extracted.
     */
    fun getLocalDir(context: Context): File {
        return context.filesDir
    }

    /**
     * Returns locally accessible directory path which contains the "tessdata" subdirectory
     * with *.traineddata files.
     */

    private fun getTargetFileForAsset(context: Context, assetName: String): File {
        val localDir = getLocalDir(context)
        return if (assetName.endsWith(".traineddata")) {
            val tessdataDir = File(localDir, Config.TESSDATA_SUBDIR)
            File(tessdataDir, assetName)
        } else {
            File(localDir, assetName)
        }
    }
    fun getTesseractDataParentDir(context: Context): String {
        return getLocalDir(context).absolutePath
    }


    fun extractAssets(context: Context) {
        val localDir = getLocalDir(context)
        if (!localDir.exists() && !localDir.mkdirs()) {
            Log.e(TAG, "Cannot create base directory: $localDir")
            return
        }

        // Create the tessdata subdirectory if it doesn't exist
        val tessdataDir = File(localDir, Config.TESSDATA_SUBDIR)
        if (!tessdataDir.exists()) {
            if (!tessdataDir.mkdirs()) {
                Log.e(TAG, "Cannot create tessdata directory: $tessdataDir")
                return // Stop if we can't create this crucial directory
            } else {
                Log.i(TAG, "Created tessdata directory: $tessdataDir")
            }
        }

        val assetsToExtract = listOf(
            Config.TESS_DATA_ENG,
            Config.DEMO_PIC
            // Add other asset file names here, including other .traineddata files
        ).distinct()

        val assetManager = context.assets

        for (assetNameInApk in assetsToExtract) {
            // Determine the final name on the filesystem (usually the same as in assets)
            val assetFileNameOnDevice = File(assetNameInApk).name // Extracts just the filename

            val targetFile = getTargetFileForAsset(context, assetFileNameOnDevice)

            if (!targetFile.exists()) {
                Log.i(TAG, "Asset '$assetNameInApk' not found at '${targetFile.absolutePath}'. Copying...")
                copyAssetFile(assetManager, assetNameInApk, targetFile)
            } else {
                Log.i(TAG, "Asset '$assetFileNameOnDevice' already exists at '${targetFile.absolutePath}'.")
            }
        }
    }
    private fun copyAssetFile(
        assetManager: AssetManager,
        assetNameInApk: String, // Name/path of the asset within the APK's assets folder
        destinationFile: File
    ) {
        // Ensure parent directory for the destination file exists
        destinationFile.parentFile?.mkdirs() // This is good, ensures tessdataDir is created

        Log.d(TAG, "Attempting to copy asset: '$assetNameInApk' to '${destinationFile.absolutePath}'") // ADD THIS LOG

        try {
            // Open the asset using its path within the assets folder
            assetManager.open(assetNameInApk).use { inputStream -> // This is where it might fail if assetNameInApk is wrong
                Log.d(TAG, "Successfully opened asset: '$assetNameInApk'") // ADD THIS LOG
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    Log.i(TAG, "Successfully copied '$assetNameInApk' to '${destinationFile.absolutePath}'")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error copying asset '$assetNameInApk' to '${destinationFile.absolutePath}'", e) // IMPORTANT LOG
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
        }
    }

    private fun copyFile(
        am: AssetManager, assetName: String,
        outFile: File
    ) {
        try {
            am.open(assetName).use { `in` ->
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while ((`in`.read(buffer).also { read = it }) != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
