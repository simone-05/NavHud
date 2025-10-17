package com.simonimal.navhud

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.MapStyleOptions

import com.google.android.libraries.navigation.AlternateRoutesStrategy
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationApi.NavigatorListener
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Navigator.RouteStatus
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.StylingOptions
import com.google.android.libraries.navigation.Waypoint
import com.google.android.libraries.navigation.Waypoint.UnsupportedPlaceIdException
import com.google.android.libraries.navigation.NavigationView


class NavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var navigationView: NavigationView
    private lateinit var map: GoogleMap
    private lateinit var navigator: Navigator
    private lateinit var routingOptions: RoutingOptions
    private lateinit var destinationWaypoint: Waypoint
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var routeChangedListener: Navigator.RouteChangedListener? = null
    private var reroutingListener: Navigator.ReroutingListener? = null
    private var isFlipped: Boolean = false
    private val timeUpdatingInterval: Long = 60000  // millisecs, how often to update current time and ETA

    private lateinit var currentTimeTextView: TextView
    private lateinit var etaTextView: TextView
    private val timeUpdateHandler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            updateETA()
            timeUpdateHandler.postDelayed(this, timeUpdatingInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        setContentView(R.layout.activity_navigation)

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        get_destination_data()      // retrieve the destination

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Get NavigationView from layout
        navigationView = findViewById(R.id.navigation_view)

        // Initialize NavigationView lifecycle
        navigationView.onCreate(savedInstanceState)

        initializeNavigationApi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    if (::navigator.isInitialized) {
                        navigator.stopGuidance()
                    }
                    stopTimeUpdates()
                    finish()
                } catch (e: Exception) {
                    Log.e("NavigationActivity", "Error on back pressed", e)
                    finish()
                }
            }
        })
    }

    /**
     * Function to retrieve the destination location from the main activity
     * Can either be a place id, or latitude and longitude coordinates
     * If available, place id has priority over coordinates (since according to google api, it gives better route predictions)
     */
    private fun get_destination_data() {
        val placeId = intent.getStringExtra("destination_placeId")
        if (placeId == null) {
            val lat = intent.getDoubleExtra("destination_latitude", Double.NaN)
            val lng = intent.getDoubleExtra("destination_longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                showToast("Invalid destination coordinates")
                finish()
                return
            }
            destinationWaypoint = Waypoint.builder()
                .setLatLng(lat, lng)
                .build()
        } else {
            try {
                destinationWaypoint = Waypoint.builder().setPlaceIdString(placeId).build()
            } catch (e: UnsupportedPlaceIdException) {
                showToast("Place ID was unsupported.")
                finish()
                return
            }
        }
    }

    private fun initializeNavigationApi() {
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav

                setNavigationAppearance()   // apply navigation ui styling

                navigationView.getMapAsync(this@NavigationActivity)

                registerNavigationListeners()

                navigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

                routingOptions = RoutingOptions()
                routingOptions.travelMode(RoutingOptions.TravelMode.DRIVING)
                routingOptions.alternateRoutesStrategy(AlternateRoutesStrategy.SHOW_NONE)   // Don't show alternate routes while navigating

                setupTimeDisplays()     // current time and ETA

                // Navigation starting function
                navigateToWaypoint(destinationWaypoint, routingOptions)
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> {
                        showToast("Error loading Navigation SDK: Your API key is invalid or not authorized to use the Navigation SDK.")
                    }
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> {
                        showToast("Error loading Navigation SDK: User did not accept the Navigation Terms of Use.")
                    }
                    NavigationApi.ErrorCode.NETWORK_ERROR -> {
                        showToast("Error loading Navigation SDK: Network error.")
                    }
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> {
                        showToast("Error loading Navigation SDK: Location permission is missing.")
                    }
                    else -> {
                        showToast("Error loading Navigation SDK: $errorCode", false)
                    }
                }
            }
        })
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.setTrafficEnabled(false)
        map.setBuildingsEnabled(false)
        map.uiSettings.isCompassEnabled = false
        map.setMapColorScheme(1)    // dark colorscheme
        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this@NavigationActivity, R.raw.map_style))

        map.setOnMapClickListener { latLng ->
            flipScreen()
        }
    }

    private fun navigateToWaypoint(destination: Waypoint, routingOptions: RoutingOptions) {
        val pendingRoute = navigator.setDestination(destination, routingOptions)

        pendingRoute.setOnResultListener { code ->
            when (code) {
                RouteStatus.OK -> {
                    navigator.setAudioGuidance(0) // no voice navigation
                    navigator.startGuidance()   // start navigation
                }
                RouteStatus.ROUTE_CANCELED -> {
                    showToast("Route guidance cancelled.")
                }
                RouteStatus.NO_ROUTE_FOUND,
                RouteStatus.NETWORK_ERROR -> {
                    showToast("Error starting guidance: $code")
                }
                else -> showToast("Error starting guidance: $code")
            }
        }
    }

    private fun registerNavigationListeners() {
        arrivalListener = Navigator.ArrivalListener {
            navigator.stopGuidance()
            stopTimeUpdates()
        }
        navigator.addArrivalListener(arrivalListener)

        // Listen for route changes to update ETA
        routeChangedListener = Navigator.RouteChangedListener {
            updateETA()
        }
        navigator.addRouteChangedListener(routeChangedListener)

        reroutingListener = Navigator.ReroutingListener {
            Log.d("NavigationActivity", "Rerouting")
            updateETA()
        }
        navigator.addReroutingListener(reroutingListener)
    }

    private fun setupTimeDisplays() {
        val rootLayout = findViewById<ViewGroup>(android.R.id.content)

        // Current time (top left)
        currentTimeTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 0)
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            setBackgroundColor(0xff000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        rootLayout.addView(currentTimeTextView)

        // ETA (top right)
        etaTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                setMargins(0, 0, 0, 0)
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            setBackgroundColor(0xff000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        rootLayout.addView(etaTextView)

        startTimeUpdates()
    }

    private fun startTimeUpdates() {
        timeUpdateHandler.post(timeUpdateRunnable)
    }

    private fun stopTimeUpdates() {
        // timeUpdateHandler.removeCallbacksAndMessages(timeUpdateRunnable)
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
    }

    private fun updateCurrentTime() {
        val format = SimpleDateFormat("hh:mm", Locale.getDefault())
        currentTimeTextView.text = format.format(Date())
    }

    private fun updateETA() {
        if (!::navigator.isInitialized) return

        // Get remaining time in seconds
        val timeAndDistance = navigator.currentTimeAndDistance
        timeAndDistance?.let {
            val remainingSeconds = it.seconds
            val etaMillis = System.currentTimeMillis() + (remainingSeconds * 1000)

            val format = SimpleDateFormat("hh:mm", Locale.getDefault())
            etaTextView.text = "ETA: ${format.format(Date(etaMillis))}"
        } ?: run {
            etaTextView.text = "ETA: --:--"
        }
    }

    override fun onStart() {
        super.onStart()
        navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navigationView.onResume()
    }

    override fun onPause() {
        navigationView.onPause()
        super.onPause()
    }

    override fun onStop() {
        navigationView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        stopTimeUpdates()
        if (::navigator.isInitialized) {
            try {
                arrivalListener?.let { navigator.removeArrivalListener(it) }
                routeChangedListener?.let { navigator.removeRouteChangedListener(it) }
                reroutingListener?.let { navigator.removeReroutingListener(it) }

                navigator.stopGuidance()

                navigator.cleanup()
            } catch (e: Exception) {
                Log.e("NavigationActivity", "Error cleaning up navigator", e)
            }
        }

        try {
            navigationView.onDestroy()
        } catch (e: Exception) {
            Log.e("NavigationActivity", "Error destroying navigation view", e)
        }

        super.onDestroy()
    }

    private fun setNavigationAppearance() {
        // Not needed since i disable the ui entirely
        // navigationView.setStylingOptions(StylingOptions()
        //     .primaryDayModeThemeColor(0x00000000.toInt())
        //     .primaryNightModeThemeColor(0x00000000.toInt())
        //     .secondaryDayModeThemeColor(0x00000000.toInt())
        //     .secondaryNightModeThemeColor(0x00000000.toInt())
        //     .headerGuidanceRecommendedLaneColor(0x11000000.toInt())
        //     .headerLargeManeuverIconColor(0xffffffff.toInt())
        //     .headerSmallManeuverIconColor(0x00000000.toInt())
        //     .headerNextStepTextColor(0x00000000.toInt())
        //     .headerNextStepTextSize(16f)
        //     .headerDistanceValueTextColor(0xffffffff.toInt())
        //     .headerDistanceUnitsTextColor(0xffcccccc.toInt())
        //     .headerDistanceValueTextSize(20f)
        //     .headerDistanceUnitsTextSize(18f)
        //     .headerInstructionsTextColor(0xff000000.toInt())
        //     .headerInstructionsFirstRowTextSize(24f)
        //     .headerInstructionsSecondRowTextSize(20f)
        // )

        // Disable top header
        navigationView.setHeaderEnabled(false)

        // Disable bottom ETA card (on the bottom) and progress bar
        navigationView.setEtaCardEnabled(false)
        navigationView.setTripProgressBarEnabled(false)

        // Keep recenter button enabled
        navigationView.setRecenterButtonEnabled(true)

        // Disable other UI elements
        navigationView.setSpeedLimitIconEnabled(false)
        navigationView.setSpeedometerEnabled(false)
    }

    /**
     * Function to flip/mirror the entire screen of the app to be able to view when the phone is oriented to the windshield
     */
    private fun flipScreen() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        isFlipped = !isFlipped
        val flipValue = if (isFlipped) -1f else 1f
        rootView.scaleY = flipValue
    }

    private fun showToast(message: String, short: Boolean = true) {
        if (short) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

}
