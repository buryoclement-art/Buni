package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// --- Common Serialization Data Classes (Moshi compatible) ---

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String // Base64 data
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null
)

// --- Retrofit API Service ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

class GeminiService(private val okHttpClient: OkHttpClient) {

    private val apiKey: String
        get() = BuildConfig.GEMINI_API_KEY

    /**
     * Downloads an image URL and converts it to high-quality compressed Base64
     */
    private suspend fun downloadAndConvertImageToBase64(imageUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (imageUrl.startsWith("data:image/svg+xml")) {
            // It's inline svg text, parse the svg xml description content to send to Gemini
            try {
                val decoded = android.net.Uri.decode(imageUrl.substringAfter("data:image/svg+xml;utf8,"))
                return@withContext Pair("text/plain", Base64.encodeToString(decoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            } catch (e: Exception) {
                return@withContext null
            }
        }

        try {
            val request = Request.Builder().url(imageUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                        return@withContext Pair("image/jpeg", base64)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    /**
     * Call Gemini to analyze the image
     */
    suspend fun analyzeImage(imageUrl: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Chave de API do Gemini não configurada. Configure o GEMINI_API_KEY no painel do AI Studio para habilitar a análise inteligente de imagens!"
        }

        val prompt = """
            Você é um analista de imagens profissional. Analise a imagem fornecida e retorne um relatório estruturado e elegante em português com os seguintes tópicos exatamente formatados em markdown:
            
            ### 📝 Descrição Geral
            [Descreva o que está presente na imagem de forma resumida e poética]
            
            ### 🏷️ Sugestões de Alt-Text (Acessibilidade)
            [Forneça 2 opções excelentes de textos alternativos para acessibilidade]
            
            ### 🎨 Paleta de Cores & Estética
            [Mencione as cores dominantes e o estilo estético predominante]
            
            ### 💡 Tags Recomendadas
            [Forneca de 5 a 10 palavras-chave relevantes separadas por vírgula]
        """.trimIndent()

        try {
            val mediaData = downloadAndConvertImageToBase64(imageUrl)
            
            val requestBody = if (mediaData != null) {
                // Multimodal request (Image + Text)
                val mimeType = mediaData.first
                val base64 = mediaData.second
                
                GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = prompt),
                                Part(inlineData = InlineData(mimeType = mimeType, data = base64))
                            )
                        )
                    )
                )
            } else {
                // Text-only fallback analysis request
                val textPrompt = "$prompt\nSe a imagem não puder ser baixada diretamente, use esta URL para inferir o que for possível: $imageUrl"
                GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = textPrompt)
                            )
                        )
                    )
                )
            }

            val response = GeminiClient.service.generateContent(apiKey, requestBody)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "Desculpe, o assistente Gemini não retornou nenhuma resposta válida de análise."
        } catch (e: Exception) {
            e.printStackTrace()
            "Erro ao analisar com o Gemini AI: ${e.localizedMessage}"
        }
    }
}
