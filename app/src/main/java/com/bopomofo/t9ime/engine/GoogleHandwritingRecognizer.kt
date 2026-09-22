package com.bopomofo.t9ime.engine

import android.content.Context
import com.bopomofo.t9ime.ui.HandwritingCanvasView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/**
 * Google ML Kit 官方高精度端側（On-Device）神經網絡手寫辨識引擎
 * 與 Gboard 同源的強大墨水筆跡識別技術，支援草書、連筆、任意筆順及中英數混寫
 */
class GoogleHandwritingRecognizer(private val context: Context) {

    private var recognizer: DigitalInkRecognizer? = null
    private var model: DigitalInkRecognitionModel? = null
    private var isModelReady = false
    private var isDownloading = false

    fun isReady(): Boolean = isModelReady
    fun isDownloadingModel(): Boolean = isDownloading

    /**
     * 初始化並檢查/下載指定語言標籤模型（預設 zh-Hant 繁體中文）
     */
    fun setup(
        languageTag: String = "zh-Hant",
        onModelReady: () -> Unit = {},
        onDownloading: () -> Unit = {},
        onDownloadFailed: (Exception) -> Unit = {}
    ) {
        val modelIdentifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
        } catch (e: Exception) {
            null
        } ?: return

        val currentModel = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        model = currentModel
        val remoteModelManager = RemoteModelManager.getInstance()

        remoteModelManager.isModelDownloaded(currentModel)
            .addOnSuccessListener { isDownloaded ->
                if (isDownloaded) {
                    val options = DigitalInkRecognizerOptions.builder(currentModel).build()
                    recognizer = DigitalInkRecognition.getClient(options)
                    isModelReady = true
                    isDownloading = false
                    onModelReady()
                } else {
                    isDownloading = true
                    onDownloading()
                    val conditions = DownloadConditions.Builder().build()
                    remoteModelManager.download(currentModel, conditions)
                        .addOnSuccessListener {
                            val options = DigitalInkRecognizerOptions.builder(currentModel).build()
                            recognizer = DigitalInkRecognition.getClient(options)
                            isModelReady = true
                            isDownloading = false
                            onModelReady()
                        }
                        .addOnFailureListener { err ->
                            isDownloading = false
                            onDownloadFailed(err)
                        }
                }
            }
            .addOnFailureListener { err ->
                onDownloadFailed(err)
            }
    }

    /**
     * 辨識筆跡並回傳候選字串列表
     */
    fun recognize(
        strokes: List<List<HandwritingCanvasView.StrokePoint>>,
        onSuccess: (List<String>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val client = recognizer
        if (client == null || !isModelReady) {
            onError(IllegalStateException("Google ML Kit handwriting model is not ready"))
            return
        }

        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (pt in stroke) {
                strokeBuilder.addPoint(Ink.Point.create(pt.x, pt.y, pt.time))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        val ink = inkBuilder.build()
        client.recognize(ink)
            .addOnSuccessListener { result ->
                val list = result.candidates.map { it.text }
                onSuccess(list)
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        isModelReady = false
    }
}
