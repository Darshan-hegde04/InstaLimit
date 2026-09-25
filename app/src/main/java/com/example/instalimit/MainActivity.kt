package com.example.instalimit

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

object AppConstants {
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val PREFS_NAME = "instalimit_prefs"
    const val KEY_LIMIT_MINUTES = "limit_minutes"
    const val KEY_USERNAMES = "usernames_set"
    const val KEY_MESSAGE_TEMPLATE = "message_template"
    const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    const val KEY_LAST_TRIGGERED_DATE = "last_triggered_date"

    const val CHANNEL_MONITOR_ID = "channel_instalimit_monitor"
    const val CHANNEL_ALERT_ID = "channel_instalimit_alerts"
    const val MONITOR_NOTIFICATION_ID = 1001
    const val ALERT_NOTIFICATION_ID = 2002

    const val DEFAULT_LIMIT_MINUTES = 60
    const val DEFAULT_MESSAGE = "Hey! Time to pause Instagram and say hi to standard offline life. Hope you are having a wonderful day!"
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    var limitMinutes: Int
        get() = prefs.getInt(AppConstants.KEY_LIMIT_MINUTES, AppConstants.DEFAULT_LIMIT_MINUTES)
        set(value) = prefs.edit().putInt(AppConstants.KEY_LIMIT_MINUTES, value).apply()

    var usernames: Set<String>
        get() = prefs.getStringSet(AppConstants.KEY_USERNAMES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(AppConstants.KEY_USERNAMES, value).apply()

    var messageTemplate: String
        get() = prefs.getString(AppConstants.KEY_MESSAGE_TEMPLATE, AppConstants.DEFAULT_MESSAGE) ?: AppConstants.DEFAULT_MESSAGE
        set(value) = prefs.edit().putString(AppConstants.KEY_MESSAGE_TEMPLATE, value).apply()

    var isMonitoringEnabled: Boolean
        get() = prefs.getBoolean(AppConstants.KEY_MONITORING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(AppConstants.KEY_MONITORING_ENABLED, value).apply()

    var lastTriggeredDate: String
        get() = prefs.getString(AppConstants.KEY_LAST_TRIGGERED_DATE, "") ?: ""
        set(value) = prefs.edit().putString(AppConstants.KEY_LAST_TRIGGERED_DATE, value).apply()

    fun addUsername(username: String) {
        val trimmed = username.trim().removePrefix("@")
        if (trimmed.isNotEmpty()) {
            val updated = usernames.toMutableSet().apply { add(trimmed) }
            usernames = updated
        }
    }

    fun removeUsername(username: String) {
        val updated = usernames.toMutableSet().apply { remove(username) }
        usernames = updated
    }
}

object UsageStatsHelper {

    fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getTodayInstagramUsageMinutes(context: Context): Int {
        if (!hasUsagePermission(context)) return 0

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val events = usageStatsManager.queryEvents(startTime, endTime)
        var totalForegroundTimeMs = 0L
        var lastResumeTimeMs = 0L

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == AppConstants.INSTAGRAM_PACKAGE) {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastResumeTimeMs = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (lastResumeTimeMs > 0) {
                            totalForegroundTimeMs += (event.timeStamp - lastResumeTimeMs)
                            lastResumeTimeMs = 0
                        }
                    }
                }
            }
        }

        if (lastResumeTimeMs > 0) {
            totalForegroundTimeMs += (endTime - lastResumeTimeMs)
        }

        return (totalForegroundTimeMs / (1000 * 60)).toInt()
    }

    fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}

class InstagramMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            AppConstants.MONITOR_NOTIFICATION_ID,
            buildMonitoringNotification()
        )

        serviceScope.launch {
            while (isActive) {
                if (prefsManager.isMonitoringEnabled) {
                    checkInstagramUsageAndTriggerIfNeeded()
                }
                delay(60_000)
            }
        }

        return START_STICKY
    }

    private fun checkInstagramUsageAndTriggerIfNeeded() {
        val todayMinutes = UsageStatsHelper.getTodayInstagramUsageMinutes(this)
        val limitMinutes = prefsManager.limitMinutes
        val todayDate = UsageStatsHelper.getTodayDateString()

        if (todayMinutes >= limitMinutes) {
            if (prefsManager.lastTriggeredDate != todayDate) {
                triggerReminderNotification()
                prefsManager.lastTriggeredDate = todayDate
            }
        }
    }

    private fun triggerReminderNotification() {
        val usernames = prefsManager.usernames.toList()
        val selectedUsername = if (usernames.isNotEmpty()) {
            "@" + usernames.random()
        } else {
            "a friend"
        }

        val template = prefsManager.messageTemplate
        val contentText = "$selectedUsername: \"$template\""

        val instagramIntent = packageManager.getLaunchIntentForPackage(AppConstants.INSTAGRAM_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/"))

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            instagramIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, AppConstants.CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Instagram Screen-Time Limit Reached!")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(AppConstants.ALERT_NOTIFICATION_ID, alertNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                AppConstants.CHANNEL_MONITOR_ID,
                "InstaLimit Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background screen-time check for Instagram."
            }

            val alertChannel = NotificationChannel(
                AppConstants.CHANNEL_ALERT_ID,
                "InstaLimit Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily screen time alerts and friendly reminders."
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildMonitoringNotification(): android.app.Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppConstants.CHANNEL_MONITOR_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("InstaLimit Active")
            .setContentText("Monitoring Instagram usage locally...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefsManager = PreferencesManager(context)
            if (prefsManager.isMonitoringEnabled) {
                val serviceIntent = Intent(context, InstagramMonitorService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        enableEdgeToEdge()
        prefsManager = PreferencesManager(this)

        setContent {
            InstaLimitTheme {
                MainScreen(prefsManager = prefsManager)
            }
        }
    }
}

@Composable
fun InstaLimitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE1306C),
            secondary = Color(0xFFF77737),
            tertiary = Color(0xFFFFDC80),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(prefsManager: PreferencesManager) {
    val context = LocalContext.current

    var isMonitoring by remember { mutableStateOf(prefsManager.isMonitoringEnabled) }
    var limitMinutes by remember { mutableIntStateOf(prefsManager.limitMinutes) }
    var usernamesList by remember { mutableStateOf(prefsManager.usernames.toList()) }
    var messageTemplate by remember { mutableStateOf(prefsManager.messageTemplate) }
    var usageMinutes by remember { mutableIntStateOf(0) }
    var hasUsagePermission by remember { mutableStateOf(UsageStatsHelper.hasUsagePermission(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            hasUsagePermission = UsageStatsHelper.hasUsagePermission(context)
            if (hasUsagePermission) {
                usageMinutes = UsageStatsHelper.getTodayInstagramUsageMinutes(context)
            }
            delay(5_000)
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        isMonitoring = enabled
        prefsManager.isMonitoringEnabled = enabled
        val serviceIntent = Intent(context, InstagramMonitorService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("InstaLimit", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (!hasUsagePermission) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Usage Access Required",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "InstaLimit needs Usage Access permission to read today's Instagram screen time locally on device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Grant Usage Access")
                            }
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotifPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasNotifPermission) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF332B00)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Notification Permission", fontWeight = FontWeight.Bold, color = Color(0xFFFFDC80))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Allow notifications so InstaLimit can remind you when your screen-time limit is reached.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text("Enable Notifications")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Monitoring", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                if (isMonitoring) "Service active (Checking every ~1 min)" else "Monitoring is paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMonitoring) Color(0xFF4CAF50) else Color.Gray
                            )
                        }
                        Switch(
                            checked = isMonitoring,
                            onCheckedChange = { toggleMonitoring(it) },
                            enabled = hasUsagePermission
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Today's Instagram Usage", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$usageMinutes min",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Limit: $limitMinutes min",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = if (limitMinutes > 0) (usageMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (progress >= 1.0f) Color.Red else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Set Daily Limit (Minutes)", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = limitMinutes.toFloat(),
                                onValueChange = {
                                    limitMinutes = it.toInt()
                                    prefsManager.limitMinutes = limitMinutes
                                },
                                valueRange = 5f..240f,
                                steps = 46,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "$limitMinutes m",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Reminder Message Template", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "This text will appear in the notification alongside a random username.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = messageTemplate,
                            onValueChange = {
                                messageTemplate = it
                                prefsManager.messageTemplate = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                    }
                }
            }

            item {
                Text(
                    "Local Usernames List (${usernamesList.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                var newUsernameInput by remember { mutableStateOf("") }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newUsernameInput,
                        onValueChange = { newUsernameInput = it },
                        placeholder = { Text("Enter Instagram @username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (newUsernameInput.isNotBlank()) {
                                prefsManager.addUsername(newUsernameInput)
                                usernamesList = prefsManager.usernames.toList()
                                newUsernameInput = ""
                            }
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Username", tint = Color.White)
                    }
                }
            }

            if (usernamesList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No usernames added yet. Add a few usernames to receive randomized reminder alerts!",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(usernamesList) { username ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("@$username", fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(
                            onClick = {
                                prefsManager.removeUsername(username)
                                usernamesList = prefsManager.usernames.toList()
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Gray)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}