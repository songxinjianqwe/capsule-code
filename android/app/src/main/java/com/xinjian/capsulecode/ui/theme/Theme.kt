package com.xinjian.capsulecode.ui.theme

import com.xinjian.capsulecode.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Linear Design System — Dark-native palette ───────────────────────────────
// Ref: DESIGN.md (Linear brand)

// Background surfaces (luminance stacking: deeper = darker)
// 整体上移一档，比原始 Linear 稍亮，减少"全黑"压迫感
private val MarketingBlack    = Color(0xFF111213) // deepest canvas
private val PanelDark         = Color(0xFF171819) // sidebar / panel bg
private val Level3Surface     = Color(0xFF212224) // elevated surfaces / cards
private val SecondarySurface  = Color(0xFF2E2F33) // hover / highest surface

// Text
private val PrimaryText       = Color(0xFFF7F8F8) // near-white, default text
private val SecondaryText     = Color(0xFFD0D6E0) // silver-gray body / desc
private val TertiaryText      = Color(0xFF8A8F98) // placeholders / metadata
private val QuaternaryText    = Color(0xFF62666D) // timestamps / disabled

// Brand & Accent (the ONLY chromatic color in the system)
private val BrandIndigo       = Color(0xFF5E6AD2) // primary CTA background
private val AccentViolet      = Color(0xFF7170FF) // interactive accent / active
private val AccentHover       = Color(0xFF828FFF) // hover state for accent

// Status
private val StatusGreen       = Color(0xFF27A644)
private val StatusEmerald     = Color(0xFF10B981)
private val ErrorRed          = Color(0xFFF87171) // 柔红，比 #EF4444 在暗底更不刺眼

// Borders (solid variants — semi-transparent rgba can't go in color roles)
private val BorderPrimary     = Color(0xFF23252A) // prominent separations
private val BorderSecondary   = Color(0xFF34343A) // slightly lighter

// ── Color scheme ─────────────────────────────────────────────────────────────

private val LinearDarkColors = darkColorScheme(
    // Brand / primary action
    primary                = AccentViolet,
    onPrimary              = PrimaryText,
    primaryContainer       = BrandIndigo,
    onPrimaryContainer     = PrimaryText,

    // Secondary — muted chrome
    secondary              = TertiaryText,
    onSecondary            = PrimaryText,
    secondaryContainer     = SecondarySurface,
    onSecondaryContainer   = SecondaryText,

    // Tertiary — status / accent hover
    tertiary               = AccentHover,
    onTertiary             = MarketingBlack,

    // Backgrounds (luminance stacking)
    background             = MarketingBlack,
    onBackground           = PrimaryText,

    // Surface layers
    surface                = PanelDark,
    onSurface              = PrimaryText,
    surfaceVariant         = Level3Surface,
    onSurfaceVariant       = SecondaryText,

    surfaceContainer       = Level3Surface,
    surfaceContainerHigh   = SecondarySurface,
    surfaceContainerHighest= SecondarySurface,
    surfaceContainerLow    = Level3Surface,  // BottomSheet 默认用此色，需与 background 有明显区分
    surfaceContainerLowest = MarketingBlack,
    surfaceTint            = AccentViolet,

    // Borders
    outline                = BorderPrimary,
    outlineVariant         = BorderSecondary,

    // Error
    error                  = ErrorRed,
    onError                = PrimaryText,
    errorContainer         = Color(0xFF7F1D1D),
    onErrorContainer       = Color(0xFFFCA5A5),

    // Inverse (used for snackbar / tooltips on dark)
    inverseSurface         = PrimaryText,
    inverseOnSurface       = MarketingBlack,
    inversePrimary         = BrandIndigo,

    // Scrim / overlay
    scrim                  = Color(0xD9000000), // rgba(0,0,0,0.85)
)

// ── Typography — 系统无衬线（Inter Variable 后续单独验证后替换）────────────────
// Weight mapping: 400 = read, 510 ≈ medium, 590 ≈ semibold

private val Sans = FontFamily(
    Font(R.font.inter_regular,  FontWeight(400)),
    Font(R.font.inter_medium,   FontWeight(510)),
    Font(R.font.inter_semibold, FontWeight(590)),
)

private val LinearTypography = Typography(
    // Display — aggressive negative letter-spacing (absolute sp), tight line-height
    displayLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510),  fontSize = 48.sp, lineHeight = 48.sp,  letterSpacing = (-1.056).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510),  fontSize = 40.sp, lineHeight = 40.sp,  letterSpacing = (-0.88).sp,  ),
    displaySmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510),  fontSize = 32.sp, lineHeight = 36.sp,  letterSpacing = (-0.704).sp),

    // Headline — transitional weight, starts relaxing letter-spacing
    headlineLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.28).sp,  ),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.24).sp,  ),
    headlineSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(590), fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.20).sp,  ),

    // Title — UI chrome, card headers
    titleLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(590), fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = (-0.165).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510), fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,        ),
    titleSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510), fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-0.165).sp),

    // Body — reading text
    bodyLarge   = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,        ),
    bodyMedium  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = (-0.165).sp),
    bodySmall   = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = (-0.13).sp,  ),

    // Label — button text, small labels, metadata
    labelLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.18).sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510), fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,       ),
    labelSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight(510), fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp,       ),
)

// ── Shapes — Linear's border-radius scale ────────────────────────────────────
// Micro(2) / Comfortable(6) / Card(8) / Panel(12) / Large(22)

private val LinearShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),   // inline badges, toolbar buttons
    small      = RoundedCornerShape(6.dp),   // buttons, inputs
    medium     = RoundedCornerShape(8.dp),   // cards, dropdowns
    large      = RoundedCornerShape(12.dp),  // panels, featured cards
    extraLarge = RoundedCornerShape(22.dp),  // large panel elements
)

@Composable
fun CapsuleAppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Linear is dark-native; light mode falls back to dark colors for now
    MaterialTheme(
        colorScheme = LinearDarkColors,
        typography  = LinearTypography,
        shapes      = LinearShapes,
        content     = content
    )
}
