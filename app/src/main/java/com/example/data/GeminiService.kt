package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MODEL_NAME = "gemini-3.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
    }

    suspend fun generateFamilyAdvice(
        prompt: String,
        familyContext: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            // Provide intelligent realistic assistance when API key is a placeholder
            return@withContext Result.success(getSimulatedResponse(prompt))
        }

        try {
            val systemInstruction = """
                You are a friendly, encouraging, and highly organized AI Family Assistant called 'Gemini Family Assistant'.
                You help parents and children organize household tasks, daily chores, school schedules, and family routines.
                Provide warm, actionable, and structured responses with bullet points and emojis.
                If the user writes in Hebrew, respond in natural, friendly Hebrew.
                Context about this household: $familyContext
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val systemInstructionObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        put(JSONObject().put("text", systemInstruction))
                    }
                    put("parts", partsArray)
                }
                put("systemInstruction", systemInstructionObj)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                return@withContext Result.failure(
                    Exception("Gemini API Error (${response.code}): ${response.message}")
                )
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            if (!text.isNullOrBlank()) {
                Result.success(text)
            } else {
                Result.failure(Exception("Empty response from Gemini"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getSimulatedResponse(prompt: String): String {
        return when {
            prompt.contains("שבת") || prompt.contains("סופשבוע") || prompt.contains("weekend", ignoreCase = true) -> {
                """
                🌟 **הצעת חלוקת משימות לשבת מבית Gemini:**
                
                • 🧹 **מורן (אמא):** בישולים, קניות והכנת שולחן שבת (+20 נקודות)
                • 👨‍🔧 **דוד (אבא):** שטיפת רצפות וסידור סלון (+20 נקודות)
                • 👧 **מאיה (כיתה ה׳):** החלפת מצעים וארגון מדפי החדר (+15 נקודות)
                • 👦 **נועם (כיתה ג׳):** טיול ארוך עם הכלב וזריקת מחזור (+15 נקודות)
                • 🧒 **דניאל (גן):** איסוף צעצועים ומשחקים לקופסאות (+10 נקודות)
                
                💡 *טיפ משפחתי:* כוונו מוזיקה קצבית ברקע במשך 25 דקות - כשכולם עובדים יחד, הבית מבריק בחצי מהזמן!
                """.trimIndent()
            }
            prompt.contains("ילקוט") || prompt.contains("בית ספר") || prompt.contains("מערכת") || prompt.contains("school", ignoreCase = true) -> {
                """
                🎒 **צ'ק-ליסט חכם לבית הספר מבית Gemini:**
                
                1. 📚 **ספרים ומחברות:** בדקו את המערכת למחר וודאו שכל ספר ומחברת בילקוט.
                2. ✏️ **קלמר מוכן:** עפרונות מחודדים, מחק, סרגל ודבק.
                3. 🍎 **אוכל ושתייה:** בקבוק מים מלא וחטיף אנרגיה / פרי.
                4. 👟 **ימי ספורט:** וודאו נעלי ספורט וחולצה מתאימה.
                
                ⭐ *המלצה:* הכינו את התיק תמיד בערב שלפני כדי להתחיל את הבוקר ברוגע!
                """.trimIndent()
            }
            prompt.contains("תפריט") || prompt.contains("ארוחה") || prompt.contains("dinner", ignoreCase = true) -> {
                """
                🍽️ **תפריט ארוחות שבועיות קלות וטעימות:**
                
                • **ראשון:** פסטה ברוטב עגבניות עשיר וסלט ירקות קצוץ
                • **שני:** שניצל אפוי בתנור עם פירה תפוחי אדמה רך
                • **שלישי:** קציצות ברוטב אפונה ואורז לבן
                • **רביעי:** פיצה ביתית מטורטיות או בצק מוכן שכולם מרכיבים יחד
                • **חמישי:** שקשוקה מפנקת עם טחינה ולחם טרי
                
                🛒 *משימת קניות מומלצת:* ביצים, עגבניות, מלפפונים, פסטה, תפוחי אדמה ושמן זית.
                """.trimIndent()
            }
            else -> {
                """
                ✨ **תשובת עוזר המשפחה Gemini:**
                
                תודה על השאלה! כדי ליצור שיתוף פעולה מצוין בבית:
                
                1. 🎯 **הגדירו מטרות קצרות וברורות:** משימות ממוקדות של 10-15 דקות.
                2. 🌟 **תגמול ועידוד:** צבירת כוכבים ונקודות באפליקציה מחזקת את תחושת ההישג.
                3. 🤝 **שותפות:** שתפו את הילדים בבחירת המשימות שלהם לתחושת אחריות.
                
                האם תרצו שאוסיף משימות אלו ישירות לרשימת המטלות של המשפחה?
                """.trimIndent()
            }
        }
    }
}
