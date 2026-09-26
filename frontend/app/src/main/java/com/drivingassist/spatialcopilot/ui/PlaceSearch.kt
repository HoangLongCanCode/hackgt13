package com.drivingassist.spatialcopilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivingassist.copilot.perception.ClientPlaceSearch
import com.drivingassist.copilot.perception.PlaceResult
import com.drivingassist.spatialcopilot.session.PlaceSearchState
import java.util.Locale
import kotlin.math.roundToInt

// The chrome's colors (CopilotScreen).
private val Mint = Color(0xFF7DFFC3)
private val Ink = Color(0xCC101614)
private val Amber = Color(0xFFFFC56B)

/** Small "Where to?" button under the status chip (LIVE); shows the current destination under it. */
@Composable
fun WhereToButton(destination: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Ink, shape)
            .border(1.dp, Mint.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 320.dp),
    ) {
        Text("Where to?", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (destination.isNotBlank()) {
            Text(destination, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "Where to?" (LIVE): the laptop searches places (Google Places through phase1, or its mock provider; the key stays
 * on the laptop) around this tablet's GPS. Searches on submit only, never per keystroke; tapping a result routes there.
 *
 * @param liveNavigation the laptop's hello says `navigation.mode == "live"`; null = no hello yet.
 */
@Composable
fun PlaceSearchPanel(
    state: PlaceSearchState,
    liveNavigation: Boolean?,
    onSearch: (String) -> Unit,
    onPick: (PlaceResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(440.dp)
            .background(Ink, shape)
            .border(1.dp, Mint.copy(alpha = 0.5f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Where to?", color = Mint, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { keyboard?.hide(); onClose() }) { Text("Close", color = Color.White) }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(ClientPlaceSearch.MAX_LENGTH) },
            singleLine = true,
            placeholder = { Text("Coffee, a place or an address") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) {
                    keyboard?.hide()
                    onSearch(query)
                }
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        val line = when {
            liveNavigation == false -> PlaceSearchState.NO_LIVE_NAVIGATION
            state.pending -> "Searching..."
            state.error != null -> state.error
            state.requestId != null && state.places.isEmpty() -> "No places found for \"${state.query}\"."
            else -> null
        }
        line?.let { Text(it, color = if (state.pending && liveNavigation != false) Color.White.copy(alpha = 0.8f) else Amber, fontSize = 13.sp) }
        if (!state.pending && state.places.isNotEmpty()) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                state.places.forEach { place ->
                    PlaceRow(place) {
                        keyboard?.hide()
                        onPick(place)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: PlaceResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(place.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.address?.takeIf { it.isNotBlank() && it != place.label }?.let {
                Text(it, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        place.distanceMeters?.let { Text(formatPlaceDistance(it), color = Mint, fontSize = 13.sp) }
    }
}

/** Straight-line distance of a search result: "850 m", "1.2 km", "14 km". */
internal fun formatPlaceDistance(meters: Double): String {
    val m = (meters.coerceAtLeast(0.0) / 10.0).roundToInt() * 10
    return when {
        m < 1000 -> "$m m"
        meters < 10_000.0 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        else -> "${(meters / 1000.0).roundToInt()} km"
    }
}
