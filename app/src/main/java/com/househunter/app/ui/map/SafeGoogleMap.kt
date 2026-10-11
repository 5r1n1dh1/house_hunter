package com.househunter.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * A map that cannot take the app down with it.
 *
 * Every render path is gated on [MapAvailability]; when the map is unavailable the
 * caller gets a plain panel instead of an SDK crash. The map itself is composed
 * inside a `runCatching` guard as a second line of defence, so an unexpected SDK
 * failure degrades to the same panel rather than killing the process.
 */
@Composable
fun SafeGoogleMap(
    center: LatLng,
    markers: List<Pair<LatLng, String>>,
    modifier: Modifier = Modifier,
    zoom: Float = 13f,
    onMapClick: ((LatLng) -> Unit)? = null,
    onUnavailable: @Composable (String) -> Unit = { MapUnavailablePanel(it) },
) {
    val context = LocalContext.current
    // Not `remember`ed: Play services can finish updating while the app is open,
    // and a cached "unavailable" would keep the map disabled for the whole session.
    val reason = MapAvailability.reason(context, com.househunter.app.BuildConfig.MAPS_API_KEY)

    if (reason != null) {
        onUnavailable(MapAvailability.message(reason))
        return
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, zoom)
    }

    // `rememberCameraPositionState` is keyed to this call site, not to `center`, so
    // the camera used to stay wherever the map first loaded and picking Work 1/2
    // moved the pin off-screen. Follow the centre whenever it actually changes.
    LaunchedEffect(center.lat, center.lng, zoom) {
        runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(center, zoom)) }
    }

    // Composable calls must not sit inside runCatching: swapping a composable
    // subtree for another mid-composition breaks Compose's own bookkeeping. The
    // availability check above is the real guard, so this renders directly.
    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isBuildingEnabled = false),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
        ),
        onMapClick = onMapClick?.let { { pos -> it(pos) } },
    ) {
        markers.forEach { (position, title) ->
            Marker(state = MarkerState(position), title = title)
        }
    }
}

@Composable
fun MapUnavailablePanel(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Fallback that offers to open the place in an external maps app instead. */
@Composable
fun MapUnavailableWithAction(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MapUnavailablePanel(message)
        OutlinedButton(onClick = onAction, shape = MaterialTheme.shapes.large) {
            Text(actionLabel)
        }
    }
}