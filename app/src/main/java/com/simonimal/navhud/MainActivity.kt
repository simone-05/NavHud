package com.simonimal.navhud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.graphics.Color

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView

import com.google.maps.android.SphericalUtil
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.*
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse
import com.google.android.libraries.places.api.net.SearchByTextRequest


import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder



class MainActivity : AppCompatActivity(), OnMapReadyCallback, OnCameraMoveStartedListener, OnMapClickListener, OnMapLongClickListener, OnMyLocationClickListener, GoogleMap.OnMarkerClickListener {

    private lateinit var map: GoogleMap
    private lateinit var rootView: View
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesApiKey: String
    private lateinit var placesClient: PlacesClient
    private lateinit var lastKnownLocation: LatLng
    private var currentMarker: Marker? = null
    private var isInfoCardShown: Boolean = false
    private var placesSessionToken: AutocompleteSessionToken? = null
    private lateinit var placesPredictions: List<AutocompletePrediction>

    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var markerInfoCard: MaterialCardView
    private lateinit var markerInfoTitle: TextView
    private lateinit var markerInfoBody: TextView
    private lateinit var markerInfoDistance: TextView
    private lateinit var closeMarkerInfoCard: ImageButton
    private lateinit var navigateToButton: ImageButton
    private lateinit var searchInput: MaterialAutoCompleteTextView
    private lateinit var adapter: ArrayAdapter<String>
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null
    private var selectedPlace: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root_layout)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        myLocationButton = findViewById(R.id.location_button)
        markerInfoCard = findViewById(R.id.marker_info_card)
        markerInfoTitle = findViewById(R.id.marker_info_card_title)
        markerInfoBody = findViewById(R.id.marker_info_card_body)
        markerInfoDistance = findViewById(R.id.marker_info_card_distance)
        closeMarkerInfoCard = findViewById(R.id.close_marker_info_card)
        navigateToButton = findViewById(R.id.navigate_to_button)

        searchInput = findViewById(R.id.search_input)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initializePlacesApi()

        mapFragment.getMapAsync(this)

        myLocationButton.setOnClickListener {
            centerOnCurrentLocation()
        }

        closeMarkerInfoCard.setOnClickListener {
            currentMarker?.remove()
            hideMarkerInfoCard()
        }

        navigateToButton.setOnClickListener {
            startNavigation()
        }

        markerInfoCard.setOnClickListener {
            currentMarker?.let { marker ->
                moveCameraToPoint(marker.position, 200)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        setupSearchBar()
    }

    private fun setupSearchBar() {
        adapter = ArrayAdapter(
            this,
            R.layout.dropdown_item
        )

        searchInput.setAdapter(adapter)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (placesSessionToken == null) {
                    placesSessionToken = AutocompleteSessionToken.newInstance()
                }
                val query = s.toString().trim()
                searchJob?.cancel()

                if (query.length >= 3) {
                    searchJob = scope.launch {
                        if (query.equals(selectedPlace, ignoreCase=true)) {
                            return@launch
                        }
                        delay(300)
                        get_places_predictions(query)
                    }
                } else {
                    adapter.clear()
                    adapter.addAll()
                    adapter.notifyDataSetChanged()
                    searchInput.dismissDropDown()
                }
            }
        })

        // Handle item selection
        searchInput.setOnItemClickListener { parent, view, position, id ->
            val selectedItem = parent.getItemAtPosition(position) as String
            selectedPlace = selectedItem
            searchInput.dismissDropDown()
            searchInput.clearFocus()
            hideKeyboard()
            get_place_details(position)
        }

        // Handle IME search action
        searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchInput.dismissDropDown()
                    searchInput.clearFocus()
                    hideKeyboard()
                    search_a_place(query)
                }
                true
            } else {
                false
            }
        }
    }

    private fun initializePlacesApi() {
        try {
            val bundle = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
            val key = bundle?.getString("com.google.android.places.API_KEY")
            if (key.isNullOrBlank()) {
                showToast("WARNING: Application built without MAPS_API_KEY", short=false)
                return
                // throw IllegalStateException("MAPS_API_KEY is missing or empty in AndroidManifest.xml")
            }
            placesApiKey = key
            Places.initializeWithNewPlacesApiEnabled(applicationContext, placesApiKey)
            placesClient = Places.createClient(this)
        } catch (e: Exception) {
            throw RuntimeException("MAPS_API_KEY missing")
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.setTrafficEnabled(false)
        map.setBuildingsEnabled(false)
        map.setPadding(0, 250, 0, 0)                     // puts the compass a bit lower on the map
        map.uiSettings.isMyLocationButtonEnabled = false    // disable default gps button
        map.uiSettings.isMapToolbarEnabled = false          // disable buttons appearing on marker click
        map.setMapColorScheme(1)                            // dark colorscheme
        // map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style))     // apply my custom map colors

        map.setOnMapClickListener(this)
        map.setOnMapLongClickListener(this)
        map.setOnMyLocationClickListener(this)
        map.setOnMarkerClickListener(this)

        enableLocation()

        centerOnCurrentLocation()
    }

    private fun enableLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableLocation()
        }
    }

    private fun getCurrentLocation(callback: (LatLng?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    callback(LatLng(location.latitude, location.longitude))
                } else {
                    callback(null)
                }
            }
        } else {
            callback(null)
        }
    }

    override fun onCameraMoveStarted(reason: Int) {
        hideKeyboard()
    }

    override fun onMapClick(point: LatLng) {
        hideMarkerInfoCard()
        hideKeyboard()
    }

    override fun onMapLongClick(point: LatLng) {
        hideMarkerInfoCard()
        hideKeyboard()
        currentMarker?.remove()
        currentMarker = map.addMarker(
            MarkerOptions()
                .position(point)
                .draggable(true)
                .title("Custom marker")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .visible(true)
        )
        currentMarker?.let { showMarkerInfoCard(it) }
    }

    /**
     * When clicking my location (the blue dot)
     */
    override fun onMyLocationClick(location: Location) {
        // showSnackbar("Current location:\n$location")
    }

    // When clicking the current location FAB
    private fun centerOnCurrentLocation() {
        getCurrentLocation { latLng ->
            if (latLng != null) {
                moveCameraToPoint(latLng)
                lastKnownLocation = latLng
            } else {
                showSnackbar("Couldn't get location")
            }
        }
    }

    override fun onMarkerClick(marker: Marker): Boolean {
        moveCameraToPoint(marker.position, 200)
        showMarkerInfoCard(marker)
        return true  // Return true to prevent default info window
    }

    private fun showMarkerInfoCard(marker: Marker, title: String? = null, body: String? = null, distance: String? = null) {
        if (isInfoCardShown) return
        isInfoCardShown = true
        currentMarker = marker
        val position = marker.position

        markerInfoTitle.text = title?: "Marker Location"
        markerInfoBody.text = body?:
            "Latitude: ${String.format("%.6f", position.latitude)}\n" +
            "Longitude: ${String.format("%.6f", position.longitude)}"
        if (distance != null) {
            markerInfoDistance.text = distance
        } else {
            if (::lastKnownLocation.isInitialized) {
                markerInfoDistance.text = SphericalUtil.computeDistanceBetween(lastKnownLocation, position).let {
                    String.format("%.3f km", it.toDouble() / 1000)
                }
            } else {
                markerInfoDistance.text = ""
            }
        }

        markerInfoCard.alpha = 0f
        markerInfoCard.visibility = View.VISIBLE
        markerInfoCard.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun hideMarkerInfoCard() {
        markerInfoCard.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                markerInfoCard.visibility = View.GONE
            }
            .start()
        isInfoCardShown = false
    }

    /**
     * Wrapper function to animate camera movement to an area between two points
     */
    private fun moveCameraToBounds(pointA: LatLng, pointB: LatLng, center: Boolean = false) {
        val cameraBounds = LatLngBounds(pointA, pointB)
        if (center) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(cameraBounds.center, 10f))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(cameraBounds, 0))
        }
    }

    /**
     * Wrapper function to animate camera movement to some coordinates
     */
    private fun moveCameraToPoint(point: LatLng, duration: Int = 500) {
        if (!::map.isInitialized) {
            Log.w("MainActivity", "Map not ready yet, skipping camera move")
            return
        }
        val cameraPosition = CameraPosition.Builder()
            .target(point)
            .zoom(15f)
            .build()

        map.animateCamera(
        CameraUpdateFactory.newCameraPosition(cameraPosition),
            duration,
            object : GoogleMap.CancelableCallback {
                override fun onFinish() {}
                override fun onCancel() {}
            }
        )
    }

    private fun startNavigation() {
        if (!::lastKnownLocation.isInitialized) {
            showToast("Couldn't get current location")
            return
        }

        val intent = Intent(this, NavigationActivity::class.java)

        val destinationLatLng = currentMarker?.position
        if (destinationLatLng == null) {
            showToast("Destination marker is not set")
            return
        }

        // If i want to use coordinates:
        intent.putExtra("destination_latitude", destinationLatLng.latitude)
        intent.putExtra("destination_longitude", destinationLatLng.longitude)

        startActivity(intent)
    }

    private fun createBoundsFromCenter(center: LatLng, radiusMeters: Double): RectangularBounds {
        val southwest = SphericalUtil.computeOffset(center, radiusMeters * Math.sqrt(2.0), 225.0)
        val northeast = SphericalUtil.computeOffset(center, radiusMeters * Math.sqrt(2.0), 45.0)
        return RectangularBounds.newInstance(southwest, northeast)
    }

    private fun get_places_predictions(query: String) {
        if (!::placesClient.isInitialized) {
            Log.e("Places Api", "placesClient is not initialized, can't retrieve places suggestions")
            return
        }

        var autocompletePlacesRequestBuilder: FindAutocompletePredictionsRequest.Builder  =
            FindAutocompletePredictionsRequest.builder()
                    .setQuery(query)
                    // .setRegionCode("US")

        if (placesSessionToken == null) {
            placesSessionToken = AutocompleteSessionToken.newInstance()
        }
        autocompletePlacesRequestBuilder.setSessionToken(placesSessionToken)

        if (::lastKnownLocation.isInitialized) {
            // val bounds = createBoundsFromCenter(lastKnownLocation, /* radius = */ 10000.0)
            val bounds: CircularBounds = CircularBounds.newInstance(lastKnownLocation, /* radius = */ 10000.0)
            autocompletePlacesRequestBuilder
                .setOrigin(lastKnownLocation)
                .setLocationBias(bounds)
        }
        val request: FindAutocompletePredictionsRequest  = autocompletePlacesRequestBuilder.build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener {
                response: FindAutocompletePredictionsResponse ->
                    placesPredictions = response.getAutocompletePredictions()
                    adapter.clear()
                    adapter.addAll(placesPredictions.map { it.getFullText(null).toString() })
                    adapter.notifyDataSetChanged()
                    searchInput.showDropDown()
            }
            .addOnFailureListener {
                exception ->
                    Log.e("Places Api", "Error while retrieving places suggestions" + exception.toString())
            }
    }

    private fun get_place_details(placesPredictionsIndex: Int) {
        val selectedPlace: AutocompletePrediction = placesPredictions[placesPredictionsIndex]
        // We get the details of the place
        val placeId = selectedPlace.getPlaceId()
        val placePrimaryName: String = selectedPlace.getPrimaryText(null).toString()
        // val placeTypes = selectedPlace.getTypes() //List<String>
        val placeTypes: String = selectedPlace.getTypes()
            .take(2)
            .joinToString(", ") { it.replace('_', ' ') }
        val placeDistance: String? = selectedPlace.getDistanceMeters()?.let {
            String.format("%.3f km", it.toDouble() / 1000)
        }

        // Then we need the coordinates of the place (a new api request) to be able to show the marker located on the map
        val placeFields = listOf(
            // Place.Field.LAT_LNG,     //instead of LOCATION for places api <4.0
            Place.Field.LOCATION,
        )

        val request = FetchPlaceRequest.newInstance(placeId, placeFields)

        placesClient.fetchPlace(request)
            .addOnSuccessListener {
                response ->
                // val fetchedPlace = response.place
                val fetchedPlace = response.getPlace()

                // fetchedPlace.latLng?.let { coordinates ->
                val coordinates: LatLng? = fetchedPlace.getLocation()
                if (coordinates == null) {
                    showToast("Can't get place location")
                    return@addOnSuccessListener
                }

                hideMarkerInfoCard()
                hideKeyboard()
                currentMarker?.remove()
                currentMarker = map.addMarker(
                    MarkerOptions()
                        .position(coordinates)
                        .draggable(false)
                        .title(placePrimaryName)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .visible(true)
                )
                currentMarker?.let {
                    showMarkerInfoCard(it,
                        placePrimaryName,
                        placeTypes,
                        placeDistance
                    )
                }
                moveCameraToPoint(coordinates)
                // }
            }
            .addOnFailureListener {
                exception ->
                    Log.e("Places Api", "Error while retrieving place details" + exception.toString())
            }

        placesSessionToken = null
    }

    private fun search_a_place(query: String) {
        if (!::placesClient.isInitialized) {
            showToast("placesClient is not initialized, can't search for places", short=false)
            Log.e("Places Api", "placesClient is not initialized, can't retrieve places suggestions")
            return
        }

        val placeFields = listOf(
            Place.Field.ID,
            // Place.Field.LAT_LNG,  //instead of LOCATION for places api <4.0
            Place.Field.LOCATION,
            // Place.Field.NAME,    //instead of DISPLAY_NAME for places api <4.0
            Place.Field.DISPLAY_NAME,
            Place.Field.TYPES
        )

        val searchByTextRequestBuilder: SearchByTextRequest.Builder = SearchByTextRequest.builder(query, placeFields)
            .setMaxResultCount(1)

        if (::lastKnownLocation.isInitialized) {
            // val bounds = createBoundsFromCenter(lastKnownLocation, /* radius = */ 10000.0)
            val bounds: CircularBounds = CircularBounds.newInstance(lastKnownLocation, /* radius = */ 10000.0)
            searchByTextRequestBuilder.setLocationBias(bounds)
        }
        val request: SearchByTextRequest  = searchByTextRequestBuilder.build()

        placesClient.searchByText(request)
            .addOnSuccessListener {
                response ->
                val places = response.getPlaces()
                if (places.isEmpty()) {
                    showToast("No results found")
                    return@addOnSuccessListener
                }
                val fetchedPlace: Place = places[0]
                // val coordinates: LatLng? = fetchedPlace.latLng
                val coordinates: LatLng? = fetchedPlace.getLocation()
                if (coordinates == null) {
                    showToast("Can't get place location")
                    return@addOnSuccessListener
                }
                // val name: String = fetchedPlace.name?: ""
                val name: String = fetchedPlace.getDisplayName()?: ""
                val types: String = fetchedPlace.getPlaceTypes()
                    ?.take(2)
                    ?.joinToString(", ") { it.replace('_', ' ') }
                    ?: ""

                hideMarkerInfoCard()
                hideKeyboard()
                currentMarker?.remove()
                currentMarker = map.addMarker(
                    MarkerOptions()
                        .position(coordinates)
                        .draggable(false)
                        .title(name)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .visible(true)
                )
                currentMarker?.let {
                    showMarkerInfoCard(it,
                        name,
                        types
                    )
                }
                moveCameraToPoint(coordinates)
            }
            .addOnFailureListener {
                exception ->
                    Log.e("Places Api", "Error while searching for the place" + exception.toString())
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showSnackbar(message: String, short: Boolean = true) {
        if (short) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).setTextColor(Color.WHITE).show()
        } else {
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).setTextColor(Color.WHITE).show()
        }
    }

    private fun showToast(message: String, short: Boolean = true) {
        if (short) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
}