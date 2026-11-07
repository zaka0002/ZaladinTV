package com.zaladintv.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val fxBrush = Brush.linearGradient(listOf(Color(0xFFA78BFF), Color(0xFF5B8CFF)))

/**
 * Focus effect for poster cards: the card lifts and grows with a spring-like ease, gets a violet-blue gradient ring and a soft halo.
 * Everything is driven by one Animatable read in the graphics/draw phase only, so moving focus never recomposes the cards (smooth D-pad scrolling).
 */
fun Modifier.posterFocus(
    corner: Dp = 14.dp,
    scaleTo: Float = 1.1f,
    base: Color = Color.Transparent,
    focusBg: Color = base,
    onFocused: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val cb = rememberUpdatedState(onFocused)
    this
        .graphicsLayer {
            val v = p.value
            val s = 1f + (scaleTo - 1f) * v
            scaleX = s; scaleY = s
            translationY = -7.dp.toPx() * v
        }
        .drawWithContent {
            val v = p.value
            val r = CornerRadius(corner.toPx())
            if (base.alpha > 0f || focusBg.alpha > 0f) drawRoundRect(lerp(base, focusBg, v), cornerRadius = r)
            drawContent()
            if (v > 0.01f) {
                val g = 7.dp.toPx()
                drawRoundRect(fxBrush, topLeft = Offset(-g / 2, -g / 2), size = Size(size.width + g, size.height + g), cornerRadius = CornerRadius(corner.toPx() + g / 2), alpha = 0.22f * v, style = Stroke(g))
                drawRoundRect(fxBrush, cornerRadius = r, alpha = v, style = Stroke(3.dp.toPx()))
            }
        }
        .onFocusChanged {
            val f = it.isFocused
            scope.launch { p.animateTo(if (f) 1f else 0f, tween(if (f) 230 else 170, easing = FastOutSlowInEasing)) }
            cb.value?.invoke(f)
        }
}

/** Keeps the focused poster at a fixed place in its row (one poster peeking before it) and glides there: a carousel instead of edge-snapping. */
@OptIn(ExperimentalFoundationApi::class)
private class AlignSpec(private val anchor: Float, private val ms: Int) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(ms, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - containerSize * anchor
}

@OptIn(ExperimentalFoundationApi::class)
private val rowSpec = AlignSpec(0.1f, 300)
@OptIn(ExperimentalFoundationApi::class)
private val pageSpec = AlignSpec(0.2f, 380)

/** Wrap a horizontal list (LazyRow) so D-pad moves glide the focused poster to a fixed slot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarouselScroll(content: @Composable () -> Unit) = CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec, content = content)

/** Wrap the vertical home scroller so moving to another shelf glides it to the top part of the screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageScroll(content: @Composable () -> Unit) = CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec, content = content)
