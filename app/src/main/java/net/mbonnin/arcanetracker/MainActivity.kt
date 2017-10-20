package net.mbonnin.arcanetracker

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.iid.FirebaseInstanceId
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    lateinit var activityView: View
    private var checkbox: CheckBox? = null
    private var mProjectionManager: MediaProjectionManager? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Utils.logWithDate("MainActivity.onCreate")

        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
                Timber.d("Firebase token: " + FirebaseInstanceId.getInstance().token!!)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }

        activityView = findViewById(R.id.activity_main)
        val button = findViewById<View>(R.id.button) as Button
        checkbox = findViewById<View>(R.id.checkbox) as CheckBox
        val permissions = findViewById<View>(R.id.permissions)

        val showNextTime = Settings.get(Settings.SHOW_NEXT_TIME, true)
        checkbox!!.isChecked = Settings.get(Settings.SHOW_NEXT_TIME, showNextTime)

        if (hasAllPermissions()) {
            permissions.visibility = View.GONE
            button.text = getString(R.string.play)
        } else {
            button.text = getString(R.string.authorizeAndPlay)
        }

        button.setOnClickListener { v -> tryToLaunchGame() }

        if (!showNextTime) {
            tryToLaunchGame()
            return
        }

        InAppBilling.get()
    }

    private fun hasAllPermissions(): Boolean {
        var has = checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED && checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            has = has and canReallyDrawOverlays()
        }

        return has
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_MEDIAPROJECTION) {
            if (resultCode == Activity.RESULT_OK) {
                val projection = mProjectionManager!!.getMediaProjection(resultCode, data)
                ScreenCapture.create(projection)
                tryToLaunchGame()
            } else {
                AlertDialog.Builder(this)
                        .setTitle(getString(R.string.hi_there))
                        .setMessage(getString(R.string.noScreenCapture))
                        .setPositiveButton(getString(R.string.ok)) { dialog, which -> dialog.dismiss() }
                        .show()
                Settings.set(Settings.SCREEN_CAPTURE_ENABLED, false)
            }
        } else {
            if (!canReallyDrawOverlays()) {
                Snackbar.make(activityView, getString(R.string.pleaseEnablePermissions), Snackbar.LENGTH_LONG).show()
            } else {
                tryToLaunchGame()
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun canReallyDrawOverlays(): Boolean {
        val canDraw = android.provider.Settings.canDrawOverlays(this)
        if (canDraw) {
            return true
        }

        // workaround google: https://issuetracker.google.com/issues/37077274#c7
        val view = View(this)
        try {
            ViewManager.get().addCenteredView(view)
        } catch (e: Exception) {
            return false
        }

        Timber.d("canDrawOverlays lied")
        ViewManager.get().removeView(view)
        return true
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Snackbar.make(activityView, getString(R.string.pleaseEnablePermissions), Snackbar.LENGTH_LONG).show()
        } else {
            tryToLaunchGame()
        }
    }

    private fun tryToLaunchGame() {
        /*
         * Do not use the application context, dialogs do not work with an application context
         */
        val context = ContextThemeWrapper(this, R.style.AppThemeLight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_PERMISSIONS)
                return
            } else if (!canReallyDrawOverlays()) {
                try {
                    val intent2 = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                    startActivityForResult(intent2, REQUEST_CODE_GET_OVERLAY_PERMISSIONS)
                } catch (e: Exception) {
                    AlertDialog.Builder(context)
                            .setTitle(getString(R.string.hi_there))
                            .setMessage(getString(R.string.overlay_explanation))
                            .setPositiveButton(getString(R.string.ok)) { dialog, which -> dialog.dismiss() }
                            .show()
                }

                return
            }
        }

        if (Settings.get(Settings.SCREEN_CAPTURE_ENABLED, true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && ScreenCapture.get() == null) {
            mProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mProjectionManager!!.createScreenCaptureIntent(), REQUEST_CODE_MEDIAPROJECTION)
            return
        }

        if (Build.MANUFACTURER.toLowerCase().contains("xiaomi") && Settings.get(Settings.SHOW_XIAOMI_WARNING, true)) {
            AlertDialog.Builder(context)
                    .setTitle(getString(R.string.hi_there))
                    .setMessage(getString(R.string.xiaomi_explanation))
                    .setNeutralButton(getString(R.string.learn_more)) { dialog, which -> Utils.openLink("https://www.reddit.com/r/arcanetracker/comments/5nygi0/read_this_if_you_are_playing_on_a_xiaomi_device/") }
                    .setPositiveButton(getString(R.string.gotIt)) { dialog, which ->
                        dialog.dismiss()
                        Settings.set(Settings.SHOW_XIAOMI_WARNING, false)
                        tryToLaunchGame()
                    }
                    .show()
            return
        }

        launchGame()
    }

    private fun launchGame() {
        val launchIntent = packageManager.getLaunchIntentForPackage(HEARTHSTONE_PACKAGE_ID)
        if (launchIntent != null) {
            Settings.set(Settings.SHOW_NEXT_TIME, checkbox!!.isChecked)

            try {
                val inputStream = resources.openRawResource(R.raw.log_config)

                val file = File(Utils.getHSExternalDir() + "log.config")
                val outputStream = FileOutputStream(file)

                val buffer = ByteArray(8192)

                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) {
                        break
                    } else if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Snackbar.make(activityView, getString(R.string.cannot_locate_heathstone_install), Snackbar.LENGTH_LONG).show()
                Utils.reportNonFatal(Exception("cannot locate hearthstone install directory", e))
            }

            startActivity(launchIntent)
            finish()

            Overlay.get().show()

            Settings.set(Settings.CHECK_IF_RUNNING, false)
        } else {
            Snackbar.make(activityView, getString(R.string.cannot_launch), Snackbar.LENGTH_LONG).show()
            Utils.reportNonFatal(Exception("no intent to launch game"))
        }
    }

    companion object {
        private val REQUEST_CODE_PERMISSIONS = 1
        private val REQUEST_CODE_GET_OVERLAY_PERMISSIONS = 2
        val REQUEST_CODE_MEDIAPROJECTION = 42
        val HEARTHSTONE_PACKAGE_ID = "com.blizzard.wtcg.hearthstone"
    }
}