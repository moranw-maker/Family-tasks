package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.FamilyIdentity
import com.example.model.FamilyInvitation
import com.example.model.FamilyMembershipState
import com.example.model.StartupState
import com.example.model.UserRole
import com.example.ui.theme.*

private enum class OnboardingMode {
    CHOICE,
    CREATE_FAMILY,
    JOIN_FAMILY
}

@Composable
fun GoogleOnboardingScreen(
    isHebrew: Boolean,
    isAuthenticating: Boolean = false,
    authErrorMessage: String? = null,
    isGoogleSignedIn: Boolean = false,
    authenticatedEmail: String = "",
    authenticatedDisplayName: String = "",
    startupState: StartupState = StartupState.OnboardingRequired(),
    membershipState: FamilyMembershipState = FamilyMembershipState.Unauthenticated,
    validatedInvitation: FamilyInvitation? = null,
    invitationErrorMessage: String? = null,
    isProcessingFamilyAction: Boolean = false,
    onLaunchRealGoogleSignIn: (
        context: android.content.Context,
        familyName: String?,
        onAuthSuccess: (email: String, displayName: String, photoUrl: String?) -> Unit
    ) -> Unit = { _, _, _ -> },
    onRetryStartupLoading: () -> Unit = {},
    onValidateInviteCode: (code: String) -> Unit = {},
    onClearValidatedInvite: () -> Unit = {},
    onConfirmJoinWithValidatedInvite: (
        code: String,
        memberName: String,
        familyIdentity: FamilyIdentity?
    ) -> Unit = { _, _, _ -> },
    onCreateFamilyAfterLogin: (
        familyName: String,
        parentName: String,
        familyIdentity: FamilyIdentity
    ) -> Unit = { _, _, _ -> },
    onSignOutGoogle: () -> Unit = {},
    onStartCleanFamily: (
        familyName: String,
        adminName: String,
        email: String,
        familyIdentity: FamilyIdentity
    ) -> Unit = { _, _, _, _ -> },
    onJoinExistingFamily: (inviteCode: String, memberName: String, email: String) -> Unit = { _, _, _ -> },
    onContinueDemoMode: () -> Unit = {}
) {
    var mode by remember { mutableStateOf(OnboardingMode.CHOICE) }

    var familyNameInput by remember { mutableStateOf("משפחת ישראלי") }
    var displayNameInput by remember(authenticatedDisplayName) {
        mutableStateOf(authenticatedDisplayName.ifBlank { "" })
    }
    var selectedParentIdentity by remember { mutableStateOf(FamilyIdentity.FATHER) }
    var inviteCodeInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    val isRestoringSessionOrProfile = startupState is StartupState.LoadingAuthentication ||
        startupState is StartupState.LoadingUserProfile ||
        startupState is StartupState.LoadingFamilyMembership ||
        (isGoogleSignedIn && membershipState is FamilyMembershipState.Checking)

    val recoverableErrorText: String? = when {
        startupState is StartupState.RecoverableError ->
            if (isHebrew) startupState.messageHe else startupState.messageEn
        membershipState is FamilyMembershipState.RecoverableError ->
            if (isHebrew) membershipState.messageHe else membershipState.messageEn
        else -> null
    }

    val recoverableDiagnosticBadge: String? = when {
        startupState is StartupState.RecoverableError && !startupState.errorCode.isNullOrBlank() ->
            listOfNotNull(startupState.errorCode, startupState.stage).joinToString(" • ")
        membershipState is FamilyMembershipState.RecoverableError && !membershipState.errorCode.isNullOrBlank() ->
            listOfNotNull(membershipState.errorCode, membershipState.stage).joinToString(" • ")
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEAF2FF),
                        Color(0xFFF3F6FC),
                        Color(0xFFFFFFFF)
                    )
                )
            )
            .testTag("google_onboarding_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Top Illustration & Branding Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_family_house),
                        contentDescription = "Family Chores Illustration",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = if (isHebrew) "המשימות המשפחתיות" else "Family Tasks ${com.example.util.AppVersionManager.displayVersionLabel}",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        fontSize = 26.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isHebrew) {
                        when {
                            isRestoringSessionOrProfile -> "משחזר את החיבור והפרופיל המשפחתי שלך..."
                            !isGoogleSignedIn -> "ניהול משימות, מערכת שעות ופרסים לכל המשפחה.\nהתחברו עם חשבון Google כדי ליצור משפחה או להצטרף עם קוד הזמנה."
                            else -> "מחובר כעת בתור: ${authenticatedDisplayName.ifBlank { authenticatedEmail }}\nבחרו האם ליצור משפחה חדשה או להצטרף למשפחה קיימת."
                        }
                    } else {
                        when {
                            isRestoringSessionOrProfile -> "Restoring your session and family profile..."
                            !isGoogleSignedIn -> "Sign in with Google to create a new family or join an existing family with an invitation code."
                            else -> "Signed in as ${authenticatedDisplayName.ifBlank { authenticatedEmail }}.\nChoose whether to create a family or join with an invite code."
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondary,
                        lineHeight = 20.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // =========================================================================
            // STATE 1: Startup State Machine Loading (Auth / Profile / Family Membership)
            // =========================================================================
            if (isRestoringSessionOrProfile) {
                val statusSubtitle = when (startupState) {
                    is StartupState.LoadingAuthentication ->
                        if (isHebrew) "מאמת חיבור קיים לחשבון Google..." else "Restoring Google authentication session..."
                    is StartupState.LoadingUserProfile ->
                        if (isHebrew) "טוען פרופיל אישי מ-Firestore..." else "Loading your persistent user profile..."
                    is StartupState.LoadingFamilyMembership ->
                        if (isHebrew) "טוען נתוני משפחה מ-Firestore..." else "Loading your family membership..."
                    else ->
                        if (isHebrew) "בודק חברות במשפחה ב-Firestore..." else "Checking family membership..."
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, OutlineLight),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("checking_membership_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            color = VibrantActionBlue,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("startup_loading_status_text")
                        )
                    }
                }
            }
            // =========================================================================
            // STATE 2: Recoverable Error during startup/membership check
            // Never fall back to onboarding or overwrite an existing profile!
            // =========================================================================
            else if (recoverableErrorText != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, GoogleRed.copy(alpha = 0.4f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("startup_recoverable_error_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = GoogleRedDark,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = if (isHebrew) "שגיאה בטעינת הפרופיל מהענן" else "Could Not Load Cloud Profile",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = recoverableErrorText,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("startup_recoverable_error_message")
                        )
                        if (recoverableDiagnosticBadge != null) {
                            Text(
                                text = recoverableDiagnosticBadge,
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary.copy(alpha = 0.75f)),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("startup_recoverable_error_diagnostic")
                            )
                        }
                        Button(
                            onClick = onRetryStartupLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("retry_startup_loading_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isHebrew) "נסה שוב לטעון פרופיל" else "Retry Loading Profile",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = onSignOutGoogle,
                            modifier = Modifier.testTag("error_switch_account_btn")
                        ) {
                            Text(
                                text = if (isHebrew) "החלף חשבון Google" else "Switch Google Account",
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
            // =========================================================================
            // STATE 3: Not yet signed in with Google -> Show Google Sign-In Card
            // =========================================================================
            else if (!isGoogleSignedIn) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val activeError = localError ?: authErrorMessage
                    if (activeError != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GoogleRedLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_error_banner")
                        ) {
                            Text(
                                text = activeError,
                                color = GoogleRedDark,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Primary Google Sign-In CTA
                    Button(
                        onClick = {
                            localError = null
                            onLaunchRealGoogleSignIn(context, null) { _, name, _ ->
                                if (displayNameInput.isBlank()) {
                                    displayNameInput = name
                                }
                            }
                        },
                        enabled = !isAuthenticating,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("start_clean_family_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isAuthenticating) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "G",
                                            fontWeight = FontWeight.ExtraBold,
                                            color = VibrantActionBlue,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Text(
                                text = if (isHebrew) "התחבר עם חשבון Google" else "Sign in with Google",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                    }

                    // Offline / Demo preview button for local testing without Google credentials
                    TextButton(
                        onClick = onContinueDemoMode,
                        modifier = Modifier.testTag("continue_demo_mode_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = VibrantActionBlue
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "המשך במצב הדגמה (משפחה לדוגמה)" else "Continue with Sample Demo Family",
                            fontWeight = FontWeight.Bold,
                            color = VibrantActionBlue
                        )
                    }
                }
            }
            // =========================================================================
            // STATE 4: Authenticated user confirmed to have NO membership -> Mandatory Onboarding
            // ("Create a family" OR "Join a family")
            // =========================================================================
            else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val activeError = localError ?: invitationErrorMessage ?: authErrorMessage
                    if (activeError != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GoogleRedLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("invite_validation_error_banner")
                        ) {
                            Text(
                                text = activeError,
                                color = GoogleRedDark,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    when (mode) {
                        OnboardingMode.CHOICE -> {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color.White,
                                border = BorderStroke(1.5.dp, VibrantActionBlue.copy(alpha = 0.25f)),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("mandatory_onboarding_choice_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "איך תרצו להתחיל?" else "How would you like to start?",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary
                                        )
                                    )

                                    Text(
                                        text = if (isHebrew)
                                            "לא נמצאה משפחה משויכת לחשבון Google זה. בחרו האם ליצור משפחה חדשה (בתור הורה) או להצטרף למשפחה קיימת עם קוד הזמנה."
                                        else
                                            "No family is linked to this Google account yet. Choose whether to create a new family (as Parent) or join an existing family with an invitation code.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                    )

                                    // Option 1: Create a family (Assigns PARENT)
                                    Button(
                                        onClick = {
                                            localError = null
                                            onClearValidatedInvite()
                                            mode = OnboardingMode.CREATE_FAMILY
                                        },
                                        shape = RoundedCornerShape(18.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("onboarding_choice_create_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.AddHome, contentDescription = null)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (isHebrew) "יצירת משפחה חדשה (הורה)" else "Create a New Family (Parent)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }

                                    // Option 2: Join an existing family with invite code
                                    OutlinedButton(
                                        onClick = {
                                            localError = null
                                            onClearValidatedInvite()
                                            mode = OnboardingMode.JOIN_FAMILY
                                        },
                                        shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.5.dp, VibrantActionBlue),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("onboarding_choice_join_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Key,
                                            contentDescription = null,
                                            tint = VibrantActionBlue
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (isHebrew) "הצטרפות למשפחה קיימת (קוד הזמנה)" else "Join Existing Family (Invite Code)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = VibrantActionBlue
                                        )
                                    }
                                }
                            }
                        }

                        OnboardingMode.CREATE_FAMILY -> {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, OutlineLight),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create_family_form_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isHebrew) "יצירת משפחה חדשה 🏠" else "Create a Family 🏠",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = TextPrimary
                                            )
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = GoogleBlueLight
                                        ) {
                                            Text(
                                                text = if (isHebrew) "תפקיד הרשאה: הורה" else "Role: PARENT",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = GoogleBlueDark,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    // Explicit Family Identity Selector (FATHER vs MOTHER)
                                    Text(
                                        text = if (isHebrew) "זהות במשפחה (נשמר בפרופיל הקבוע שלך):" else "Family Identity (saved persistently):",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )

                                    ParentIdentitySelectorRow(
                                        selectedIdentity = selectedParentIdentity,
                                        isHebrew = isHebrew,
                                        fatherTag = "select_identity_father_btn",
                                        motherTag = "select_identity_mother_btn",
                                        onSelectIdentity = { identity ->
                                            selectedParentIdentity = identity
                                            if (displayNameInput.isBlank() || displayNameInput == "אבא" || displayNameInput == "אמא" || displayNameInput == "Father" || displayNameInput == "Mother") {
                                                displayNameInput = if (isHebrew) identity.labelHe else identity.labelEn
                                            }
                                        }
                                    )

                                    OutlinedTextField(
                                        value = familyNameInput,
                                        onValueChange = {
                                            familyNameInput = it
                                            localError = null
                                        },
                                        label = { Text(if (isHebrew) "שם המשפחה (למשל: משפחת כהן)" else "Family Name") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("onboarding_family_name_input")
                                    )

                                    OutlinedTextField(
                                        value = displayNameInput,
                                        onValueChange = {
                                            displayNameInput = it
                                            localError = null
                                        },
                                        label = { Text(if (isHebrew) "השם שלך במשפחה (למשל: אבא / אמא)" else "Your Display Name") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("onboarding_admin_name_input")
                                    )

                                    Button(
                                        onClick = {
                                            if (familyNameInput.isBlank()) {
                                                localError = if (isHebrew) "נא להזין את שם המשפחה" else "Please enter a family name"
                                                return@Button
                                            }
                                            localError = null
                                            val defaultTitle = if (isHebrew) selectedParentIdentity.labelHe else selectedParentIdentity.labelEn
                                            onCreateFamilyAfterLogin(
                                                familyNameInput.trim(),
                                                displayNameInput.trim().ifBlank { authenticatedDisplayName.ifBlank { defaultTitle } },
                                                selectedParentIdentity
                                            )
                                        },
                                        enabled = !isProcessingFamilyAction,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("confirm_create_family_btn")
                                    ) {
                                        if (isProcessingFamilyAction) {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        val identityText = if (isHebrew) selectedParentIdentity.labelHe else selectedParentIdentity.labelEn
                                        Text(
                                            text = if (isHebrew) "צור משפחה והיכנס בתור $identityText (הורה)" else "Create Family as $identityText (Parent)",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            localError = null
                                            mode = OnboardingMode.CHOICE
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text(if (isHebrew) "חזרה לבחירה" else "Back")
                                    }
                                }
                            }
                        }

                        OnboardingMode.JOIN_FAMILY -> {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, OutlineLight),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("join_family_form_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "הצטרפות למשפחה קיימת 🔑" else "Join Existing Family 🔑",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary
                                        )
                                    )

                                    Text(
                                        text = if (isHebrew)
                                            "הזינו את קוד ההזמנה בן 6 התווים שקיבלתם מההורה ב-WhatsApp. התפקיד שלכם במשפחה (ילד/ה או הורה) נקבע אוטומטית לפי ההזמנה."
                                        else
                                            "Enter the 6-character invitation code shared by a parent. Your role (Child or Parent) is determined by the invitation.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                    )

                                    OutlinedTextField(
                                        value = inviteCodeInput,
                                        onValueChange = {
                                            inviteCodeInput = it.trim().uppercase().take(12)
                                            localError = null
                                            if (validatedInvitation != null) {
                                                onClearValidatedInvite()
                                            }
                                        },
                                        label = { Text(if (isHebrew) "קוד הזמנה (למשל: K7M9P2)" else "Invitation Code") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("onboarding_invite_code_input")
                                    )

                                    OutlinedTextField(
                                        value = displayNameInput,
                                        onValueChange = {
                                            displayNameInput = it
                                            localError = null
                                        },
                                        label = { Text(if (isHebrew) "השם שלך במשפחה (למשל: דניאל)" else "Your Name in the Family") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("onboarding_join_name_input")
                                    )

                                    // Step 1: Validate the invitation code
                                    if (validatedInvitation == null) {
                                        Button(
                                            onClick = {
                                                if (inviteCodeInput.trim().length < 4) {
                                                    localError = if (isHebrew) "נא להזין קוד הזמנה חוקי" else "Please enter a valid invite code"
                                                    return@Button
                                                }
                                                localError = null
                                                onValidateInviteCode(inviteCodeInput.trim())
                                            },
                                            enabled = !isProcessingFamilyAction,
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp)
                                                .testTag("validate_invite_code_btn")
                                        ) {
                                            if (isProcessingFamilyAction) {
                                                CircularProgressIndicator(
                                                    color = Color.White,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(
                                                text = if (isHebrew) "בדיקת קוד הזמנה" else "Verify Invitation Code",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Step 2: Show validated family name & immutable role, then Confirm Joining
                                    AnimatedVisibility(visible = validatedInvitation != null) {
                                        validatedInvitation?.let { invite ->
                                            val isParentInvite = invite.invitedRole == UserRole.PARENT
                                            val roleBadgeText = if (isParentInvite) {
                                                if (isHebrew) "הורה (מנהל משפחה)" else "Parent (Family Admin)"
                                            } else {
                                                if (isHebrew) "ילד/ה (חבר משפחה)" else "Child (Family Member)"
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(18.dp),
                                                color = PastelGreenBackground,
                                                border = BorderStroke(1.5.dp, PastelGreenBorder),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("validated_invite_confirmation_card")
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Verified,
                                                            contentDescription = null,
                                                            tint = PastelGreenDark
                                                        )
                                                        Text(
                                                            text = if (isHebrew) "קוד הזמנה תקף אומת!" else "Valid Invitation Verified!",
                                                            style = MaterialTheme.typography.titleSmall.copy(
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = PastelGreenDark
                                                            )
                                                        )
                                                    }

                                                    Text(
                                                        text = if (isHebrew)
                                                            "🏠 משפחה: ${invite.familyName}"
                                                        else
                                                            "🏠 Family: ${invite.familyName}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = TextPrimary
                                                        ),
                                                        modifier = Modifier.testTag("validated_invite_family_name")
                                                    )

                                                    Text(
                                                        text = if (isHebrew)
                                                            "👤 תפקיד בהזמנה: $roleBadgeText (קבוע)"
                                                        else
                                                            "👤 Assigned Role: $roleBadgeText (Locked)",
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isParentInvite) GoogleBlueDark else PastelGreenDark
                                                        ),
                                                        modifier = Modifier.testTag("validated_invite_role_label")
                                                    )

                                                    if (invite.invitingParentName.isNotBlank()) {
                                                        Text(
                                                            text = if (isHebrew)
                                                                "✉️ הוזמן על ידי: ${invite.invitingParentName}"
                                                            else
                                                                "✉️ Invited by: ${invite.invitingParentName}",
                                                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                                        )
                                                    }

                                                    if (isParentInvite) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = if (isHebrew) "בחרו את זהות ההורה שלכם:" else "Select your Parent Identity:",
                                                            style = MaterialTheme.typography.labelMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                color = TextPrimary
                                                            )
                                                        )
                                                        ParentIdentitySelectorRow(
                                                            selectedIdentity = selectedParentIdentity,
                                                            isHebrew = isHebrew,
                                                            fatherTag = "join_select_identity_father_btn",
                                                            motherTag = "join_select_identity_mother_btn",
                                                            onSelectIdentity = { selectedParentIdentity = it }
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    Button(
                                                        onClick = {
                                                            val chosenName = displayNameInput.trim()
                                                                .ifBlank { invite.invitedNickname.ifBlank { authenticatedDisplayName.ifBlank { "חבר משפחה" } } }
                                                            val resolvedJoinIdentity = if (isParentInvite) {
                                                                selectedParentIdentity
                                                            } else {
                                                                FamilyIdentity.CHILD
                                                            }
                                                            onConfirmJoinWithValidatedInvite(
                                                                invite.code,
                                                                chosenName,
                                                                resolvedJoinIdentity
                                                            )
                                                        },
                                                        enabled = !isProcessingFamilyAction,
                                                        shape = RoundedCornerShape(14.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(48.dp)
                                                            .testTag("confirm_join_family_btn")
                                                    ) {
                                                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = if (isHebrew) "אישור והצטרפות למשפחה" else "Confirm & Join Family",
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    TextButton(
                                        onClick = {
                                            localError = null
                                            onClearValidatedInvite()
                                            mode = OnboardingMode.CHOICE
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    ) {
                                        Text(if (isHebrew) "חזרה לבחירה" else "Back")
                                    }
                                }
                            }
                        }
                    }

                    // Switch Google Account / Sign out button
                    TextButton(
                        onClick = onSignOutGoogle,
                        modifier = Modifier.testTag("onboarding_switch_account_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "החלף חשבון Google" else "Switch Google Account",
                            color = TextSecondary
                        )
                    }
                }
            }

            // Bottom Security Footer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isHebrew)
                        "מאובטח באמצעות Google Identity & Firebase ${com.example.util.AppVersionManager.displayVersionLabel}"
                    else
                        "Secured by Google Identity & Firebase ${com.example.util.AppVersionManager.displayVersionLabel}",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                )
            }
        }
    }
}

@Composable
private fun ParentIdentitySelectorRow(
    selectedIdentity: FamilyIdentity,
    isHebrew: Boolean,
    fatherTag: String,
    motherTag: String,
    onSelectIdentity: (FamilyIdentity) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val isFatherSelected = selectedIdentity == FamilyIdentity.FATHER
        val isMotherSelected = selectedIdentity == FamilyIdentity.MOTHER

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isFatherSelected) VibrantActionBlue.copy(alpha = 0.14f) else SurfaceVariantLight,
            border = BorderStroke(
                width = if (isFatherSelected) 2.dp else 1.dp,
                color = if (isFatherSelected) VibrantActionBlue else OutlineLight
            ),
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onSelectIdentity(FamilyIdentity.FATHER) }
                .testTag(fatherTag)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                Text(text = "👨", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isHebrew) "אבא (Father)" else "Father (אבא)",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isFatherSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isFatherSelected) VibrantActionBlue else TextPrimary
                    )
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isMotherSelected) VibrantActionBlue.copy(alpha = 0.14f) else SurfaceVariantLight,
            border = BorderStroke(
                width = if (isMotherSelected) 2.dp else 1.dp,
                color = if (isMotherSelected) VibrantActionBlue else OutlineLight
            ),
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onSelectIdentity(FamilyIdentity.MOTHER) }
                .testTag(motherTag)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                Text(text = "👩", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isHebrew) "אמא (Mother)" else "Mother (אמא)",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isMotherSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isMotherSelected) VibrantActionBlue else TextPrimary
                    )
                )
            }
        }
    }
}

