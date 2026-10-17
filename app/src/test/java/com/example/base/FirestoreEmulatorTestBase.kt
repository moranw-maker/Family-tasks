package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

  protected lateinit var firestore: FirebaseFirestore
  protected lateinit var auth: FirebaseAuth
  protected lateinit var databaseId: String

  private lateinit var appContext: Context
  private val userApps = mutableMapOf<String, Pair<FirebaseAuth, FirebaseFirestore>>()

  @Before
  open fun setUpFirebase() {
    appContext = ApplicationProvider.getApplicationContext<Context>()
    databaseId = appContext.getString(com.example.R.string.firestore_database_id)
    syncRulesToEmulatorDatabase(databaseId)
    userApps.clear()

    val unauthAppName = "emulatorUnauth_${System.identityHashCode(appContext)}_${System.nanoTime()}"
    val app = FirebaseApp.initializeApp(
      appContext,
      FirebaseOptions.Builder()
        .setApplicationId("com.example.app")
        .setProjectId(PROJECT_ID)
        .setApiKey("fake-api-key-for-emulator")
        .build(),
      unauthAppName
    )

    auth = FirebaseAuth.getInstance(app)
    try {
      auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
    } catch (_: IllegalStateException) {
    }
    auth.signOut()

    firestore = FirebaseFirestore.getInstance(app, databaseId)
    try {
      firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
      firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
        .build()
    } catch (_: IllegalStateException) {
    }
  }

  @After
  open fun tearDownFirebase() {
    userApps.clear()
  }

  private val directExecutor = java.util.concurrent.Executor { it.run() }

  private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitDirect(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
      addOnSuccessListener(directExecutor) { if (cont.isActive) cont.resumeWith(Result.success(it)) }
      addOnFailureListener(directExecutor) { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    }

  private suspend fun flushMainLooper() =
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
      android.os.Handler(android.os.Looper.getMainLooper()).post {
        if (cont.isActive) cont.resumeWith(Result.success(Unit))
      }
    }

  protected suspend fun signInTestUser(email: String): String = withContext(Dispatchers.IO) {
    val existing = userApps[email]
    if (existing != null && existing.first.currentUser != null) {
      auth = existing.first
      firestore = existing.second
      flushMainLooper()
      return@withContext checkNotNull(auth.currentUser?.uid)
    }

    val cleanEmail = email.replace(Regex("[^a-zA-Z0-9]"), "_")
    val userAppName = "emulatorUser_${System.identityHashCode(appContext)}_${cleanEmail}_${System.nanoTime()}"
    val userApp = FirebaseApp.initializeApp(
      appContext,
      FirebaseOptions.Builder()
        .setApplicationId("com.example.app")
        .setProjectId(PROJECT_ID)
        .setApiKey("fake-api-key-for-emulator")
        .build(),
      userAppName
    )

    val userAuth = FirebaseAuth.getInstance(userApp)
    try {
      userAuth.useEmulator(EMULATOR_HOST, AUTH_PORT)
    } catch (_: IllegalStateException) {
    }

    val result = withTimeout(AUTH_TIMEOUT_MS) {
      try {
        userAuth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).awaitDirect()
      } catch (unused: Exception) {
        userAuth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).awaitDirect()
      }
    }
    val user = checkNotNull(result.user) { "User auth failed" }
    user.getIdToken(true).awaitDirect()
    flushMainLooper()

    // Instantiate FirebaseFirestore AFTER userAuth is already signed in with a cached token
    val userFirestore = FirebaseFirestore.getInstance(userApp, databaseId)
    try {
      userFirestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
      userFirestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
        .build()
    } catch (_: IllegalStateException) {
    }
    flushMainLooper()

    userApps[email] = Pair(userAuth, userFirestore)
    auth = userAuth
    firestore = userFirestore
    checkNotNull(userAuth.currentUser?.uid) { "Current user null" }
  }

  companion object {
    const val EMULATOR_HOST = "127.0.0.1"
    const val FIRESTORE_PORT = 8085
    const val AUTH_PORT = 9099
    const val PROJECT_ID = "demo-no-project"
    const val DEFAULT_PASSWORD = "password123"
    const val AUTH_TIMEOUT_MS = 5000L

    @Volatile
    private var rulesSynced = false

    fun syncRulesToEmulatorDatabase(dbId: String) {
      if (rulesSynced) return
      runCatching {
        val rulesFile = listOf(java.io.File("../firestore.rules"), java.io.File("firestore.rules"))
          .firstOrNull { it.exists() } ?: return
        val rulesText = rulesFile.readText()
        val body = org.json.JSONObject().apply {
          put("rules", org.json.JSONObject().apply {
            put("files", org.json.JSONArray().apply {
              put(org.json.JSONObject().apply {
                put("name", "security.rules")
                put("content", rulesText)
              })
            })
          })
        }.toString().toByteArray(Charsets.UTF_8)
        for (targetDb in listOf("(default)", dbId)) {
          val url = java.net.URL("http://$EMULATOR_HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT_ID/databases/$targetDb:securityRules")
          val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 2000
            readTimeout = 2000
          }
          conn.outputStream.use { it.write(body) }
          conn.responseCode
          conn.disconnect()
        }
        rulesSynced = true
      }
    }
  }
}
