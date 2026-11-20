package com.example

import android.content.Context
import com.example.ai.GeminiClipAnalyzer
import com.example.data.ClipRepository
import com.example.data.local.ClipPulseDatabase
import com.example.media.FaceReframeAnalyzer
import com.example.media.Media3Engine

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: ClipPulseDatabase = ClipPulseDatabase.getInstance(appContext)

    val repository: ClipRepository = ClipRepository(appContext, database.clipPulseDao())
    val geminiClipAnalyzer: GeminiClipAnalyzer = GeminiClipAnalyzer()
    val faceReframeAnalyzer: FaceReframeAnalyzer = FaceReframeAnalyzer(appContext)
    val media3Engine: Media3Engine = Media3Engine(appContext)
}
