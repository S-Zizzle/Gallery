package org.fossify.gallery.helpers

import android.content.Context
import android.util.Log
import org.fossify.gallery.extensions.config
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

class TaggerHelper(private val context: Context) {

    companion object {
        private const val TAG = "TaggerHelper"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILENAME"
        const val TAG_PROMPT =
            "List 8-10 tags for this photo. Cover: subjects (people, animals, objects), " +
            "activity or event, location type, and mood or style. " +
            "Be specific rather than generic (prefer 'ramen' over 'food', 'torii gate' over 'structure'). " +
            "Output comma-separated lowercase tags only, no extra text."
    }

    fun isModelAvailable(): Boolean = getModelFile().exists()

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun createEngineConfig(): EngineConfig = EngineConfig(
        modelPath = getModelFile().absolutePath,
        backend = Backend.CPU(),
        visionBackend = Backend.CPU(),
        maxNumImages = 1
    )

    fun tagImageWithEngine(engine: Engine, imagePath: String): List<String> {
        return try {
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of("You are a photo tagging assistant. Generate descriptive tags that help find photos by searching."),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )
            engine.createConversation(conversationConfig).use { conversation ->
                val prompt = context.config.aiTagPrompt
                val contents = Contents.of(Content.ImageFile(imagePath), Content.Text(prompt))
                val response = conversation.sendMessage(Message.user(contents))
                val text = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                parseTags(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to tag image: $imagePath", e)
            emptyList()
        }
    }

    fun tagImage(imagePath: String): List<String> {
        if (!isModelAvailable()) {
            Log.e(TAG, "Model not available at ${getModelFile().absolutePath}")
            return emptyList()
        }
        return try {
            val engine = Engine(createEngineConfig())
            engine.initialize()
            engine.use { tagImageWithEngine(it, imagePath) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize engine", e)
            emptyList()
        }
    }

    internal fun parseTags(response: String): List<String> {
        return response
            .lowercase()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
            .distinct()
            .take(10)
    }
}
