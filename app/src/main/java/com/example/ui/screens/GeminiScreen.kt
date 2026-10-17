package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun GeminiScreen(
    currentMember: FamilyMember,
    members: List<FamilyMember>,
    isHebrew: Boolean,
    onAskGemini: (prompt: String) -> Unit,
    onAddSuggestedTasks: () -> Unit,
    isLoading: Boolean,
    responseContent: String?,
    onShowToast: (String) -> Unit
) {
    var userPrompt by remember { mutableStateOf("") }

    val presetPrompts = listOf(
        "🌟 הצע חלוקת משימות לניקיון לשבת לכל המשפחה" to "Suggest weekend cleaning chores for everyone",
        "🎒 הכן צ'ק-ליסט ציוד וילקוט לביה״ס למחר" to "Prepare school bag checklist for tomorrow",
        "🍽️ הצע תפריט ארוחות ערב שבועי קל ומזין" to "Suggest easy weekly dinner menu",
        "🏆 איך לבנות שיטת ניקוד ותגמול מעודדת לילדים?" to "How to motivate kids with points & rewards?"
    )

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFFEBF3FE),
                        androidx.compose.ui.graphics.Color(0xFFF7F9FD),
                        androidx.compose.ui.graphics.Color.White
                    )
                )
            )
    ) {
        // Quick Screen Help Guide
        item {
            com.example.ui.components.ScreenHelpBanner(
                title = if (isHebrew) "עוזר ה-AI של Gemini" else "Gemini AI Family Assistant",
                tips = if (isHebrew) listOf(
                    "הצעת משימות: שאלו את Gemini לרעיונות למטלות שבועיות מותאמות לגילאי הילדים.",
                    "חלוקה שווה: בקשו חלוקת תורנויות מאוזנת בין כל בני המשפחה.",
                    "הוספה בקליק: משימות ש-Gemini מציע ניתנות להוספה ישירה לרשימת המשימות.",
                    "סיוע בלימודים: עזרה ברעיונות לפרויקטים ושיעורי בית."
                ) else listOf(
                    "Task Suggestions: Ask Gemini for chore suggestions tailored to your kids.",
                    "Fair Balance: Request equal chore distributions across the family.",
                    "One-Tap Add: Add suggested tasks directly to your household list.",
                    "Homework Ideas: Get school project and schedule tips."
                ),
                isHebrew = isHebrew
            )
        }

        // Hero Header Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = GoogleBlueLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(GoogleBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✨", fontSize = 24.sp)
                            }

                            Column {
                                Text(
                                    text = if (isHebrew) "עוזר המשפחה החכם של Gemini" else "Gemini Family Assistant",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlueDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "בינה מלאכותית לארגון משימות הבית, מערכות שעות ותפריטים"
                                    else
                                        "AI-powered chores planner, school checklist & advice",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Caution notice for prototype mode as required by Gemini API guidelines
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = "ℹ️ Powered by Google Gemini (gemini-3.5-flash) • Prototype Mode",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Quick Preset Suggestions
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isHebrew) "שאלות ורעיונות מהירים:" else "Quick Suggestions:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(8.dp))

                presetPrompts.forEach { (heb, eng) ->
                    val promptText = if (isHebrew) heb else eng
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                userPrompt = promptText
                                onAskGemini(promptText)
                            }
                            .testTag("preset_prompt_${promptText.hashCode()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = promptText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = GoogleBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Custom Prompt Input Box
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isHebrew) "שאלו את Gemini כל שאלה למשפחה:" else "Ask Gemini anything for your family:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = userPrompt,
                        onValueChange = { userPrompt = it },
                        placeholder = {
                            Text(
                                text = if (isHebrew)
                                    "לדוגמה: הצע 3 משימות יומיות לנועם ומאיה עם ניקוד..."
                                else
                                    "e.g. Suggest 3 daily chores for kids with points..."
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_prompt_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                if (userPrompt.isNotBlank() && !isLoading) {
                                    onAskGemini(userPrompt)
                                }
                            },
                            enabled = userPrompt.isNotBlank() && !isLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            modifier = Modifier.testTag("gemini_send_btn")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = if (isHebrew) "מעבד..." else "Thinking...")
                            } else {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isHebrew) "שאל את Gemini" else "Ask Gemini",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Response Section
        item {
            AnimatedVisibility(visible = responseContent != null || isLoading) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, GoogleBlue.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "✨", fontSize = 20.sp)
                            Text(
                                text = if (isHebrew) "תשובת Gemini למשפחה:" else "Gemini's Response:",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isLoading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = GoogleBlue, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isHebrew) "Gemini מכין את התשובה והתוכנית..." else "Gemini is preparing your plan...",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                                )
                            }
                        } else if (responseContent != null) {
                            Text(
                                text = responseContent,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Action button: Add suggested chores directly to the family app!
                            Button(
                                onClick = {
                                    onAddSuggestedTasks()
                                    onShowToast(if (isHebrew) "המשימות נוספו בהצלחה לרשימת המטלות! 🎉" else "Chores added to task list! 🎉")
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gemini_add_chores_btn")
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isHebrew) "שבץ משימות אלו ברשימת המטלות המשפחתית (+)" else "Add these tasks to family chores (+)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
