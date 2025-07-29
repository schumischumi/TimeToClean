package com.example.timetoclean;

import com.googlecode.tesseract.android.TessBaseAPI


object Config {
    const val TESS_ENGINE: Int = TessBaseAPI.OEM_LSTM_ONLY

    const val TESS_LANG: String = "7seg"

    const val TESS_DATA_ENG: String = "7seg.traineddata"

    const val TESSDATA_SUBDIR = "tessdata"

    const val DEMO_PIC = "fake_recipe.jpg"
}
