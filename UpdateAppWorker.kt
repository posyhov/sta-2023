package pl.xkom.ekspozycja.workers

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateAppWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        AppUpdateManager(applicationContext).checkAndUpdateApp()
        return Result.success()
    }
}

fun scheduleAppUpdateCheck(context: Context) {
    val constraints = Constraints.Builder()
        .build()

    val updateRequest = PeriodicWorkRequest.Builder(
        UpdateAppWorker::class.java,
        4, TimeUnit.HOURS)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "CheckAppUpdate",
        ExistingPeriodicWorkPolicy.KEEP,
        updateRequest
    )

    /*
    //TEST ONLY
    val updateRequest = OneTimeWorkRequest.Builder(
            UpdateAppWorker::class.java)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueue(updateRequest)
     */
}

open class AppUpdateManager(private val context: Context) {
    companion object {
        private const val APK_DOWNLOAD_URL = "http://serwer2350297.home.pl/app/ekspozycja.apk"
        private const val APK_VERSION_JSON = "http://serwer2350297.home.pl/app/version.json"
        private const val fileName = "ekspozycja.apk"
    }

    private val downloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    fun checkAndUpdateApp() {
        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient()
            val request = Request.Builder().url(APK_VERSION_JSON).build()
            val response = client.newCall(request).execute()
            val jsonResponse = JSONObject(response.body.string())
            val remoteVersionCode = jsonResponse.getInt("version_code")

            val packageInfo = context.packageManager.getPackageInfoCompat(context.packageName)
            val localVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

            if (remoteVersionCode > localVersionCode) {
                downloadPackage()
            }
            else
                return@launch
        }
    }
    private fun downloadPackage() {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            Timber.d("File exist. Trying to install.")
            installPackage(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
        }
        else {
            val request = DownloadManager.Request(Uri.parse(APK_DOWNLOAD_URL))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (downloadId != -1L) {
                            val fileUri = downloadManager.getUriForDownloadedFile(downloadId)
                            if (fileUri != null) {
                                installPackage(fileUri)
                            } else {
                                Timber.d("fileUri is null")
                            }
                            context?.unregisterReceiver(this)
                        }
                    }
                }
            }
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
            downloadManager.enqueue(request)
        }
    }
    private fun installPackage(downloadId: Uri) {
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        sessionParams.setAppPackageName(context.packageName)

        val sessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(sessionId)

        val outputStream = session.openWrite("ekspozycja", 0, -1)
        val inputStream = context.contentResolver.openInputStream(downloadId)

        outputStream.use { output ->
            inputStream?.use { input ->
                input.copyTo(output)
            }
        }

        val intentFilter = IntentFilter(PackageInstaller.ACTION_SESSION_COMMITTED).apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        context.registerReceiver(installStatusReceiver, intentFilter)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, installStatusReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session.commit(pendingIntent.intentSender)
        session.close() //ZMIANA
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("installStatusReceiver onReceive")

            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_SUCCESS) {
                Timber.tag("updateApp").d("App installation successful")
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                try {
                    if (file.exists()) {
                        if (file.delete()) {
                            Timber.d("File deleted successfully.")
                        } else {
                            Timber.d("File could not be deleted.")
                        }
                    } else {
                        Timber.d("File does not exist.")
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "SecurityException while deleting the file.")
                } catch (e: Exception) {
                    Timber.e(e, "Exception while deleting the file.")
                }

                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Timber.tag("updateApp").e("Couldn't find launch intent to restart the app")
                }
            }
            else {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val statusMessage = getInstallStatusMessage(status)
                Timber.tag("updateApp").e("App installation failed: $statusMessage")
            }
            context.unregisterReceiver(this)
        }
    }


    private fun getInstallStatusMessage(status: Int): String {
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installation successful."
            PackageInstaller.STATUS_FAILURE -> "Installation failed."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Installation conflict."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Installation incompatible."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Installation invalid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Installation failed due to storage issues."
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Installation pending user action."
            else -> "Unknown installation status."
        }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int = 0): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") getPackageInfo(packageName, flags)
        }
}