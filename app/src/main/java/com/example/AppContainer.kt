package com.example

import android.content.Context
import com.example.ai.GeminiClipAnalyzer
import com.example.data.ClipRepository
import com.example.data.local.ClipPulseDatabase
import com.example.media.ExportNotificationManager
import com.example.media.FaceReframeAnalyzer
import com.example.media.Media3Engine
import com.example.media.SampleVideoGenerator
import com.example.media.VideoExportCoordinator

class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database: ClipPulseDatabase = ClipPulseDatabase.getInstance(appContext)

    val repository: ClipRepository = ClipRepository(appContext, database.clipPulseDao())
    val geminiClipAnalyzer: GeminiClipAnalyzer = GeminiClipAnalyzer()
    val faceReframeAnalyzer: FaceReframeAnalyzer = FaceReframeAnalyzer(appContext)
    val media3Engine: Media3Engine = Media3Engine(appContext)
    val exportNotificationManager: ExportNotificationManager = ExportNotificationManager(appContext)
    val exportCoordinator: VideoExportCoordinator = VideoExportCoordinator(
        appContext = appContext,
        notificationManager = exportNotificationManager
    )
    val sampleVideoGenerator: SampleVideoGenerator = SampleVideoGenerator(appContext)

    companion object {
        @Volatile
        private var INSTANCE: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppContainer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
