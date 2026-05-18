# Beta — UI Redesign Implementation Spec

> **Audience:** Codex (working in `gandharvbakshi/beta`, branch `master`)
> **Goal:** Replace the current austere Material-default UI of `MainActivity` with a warm, friendly, accessibility-first layout designed for a 60-year-old primary user who orders from Blinkit and Swiggy Instamart by voice.
> **Scope:** Visual + copy redesign only. **Do not** change `MainActivity.kt` business logic, IDs, click handlers, or any of the Kotlin code beyond what is called out explicitly. All existing view IDs must remain so the activity continues to wire up correctly.

---

## 0. Design principles (read first)

1. **Calm, not clinical.** Soft cream background, deep warm-charcoal text, a single warm accent (terracotta). No neon blue, no gradients.
2. **Big, legible, generous.** Body type ≥ 17sp, headings ≥ 24sp, touch targets ≥ 64dp, generous padding (24dp page gutter, 18dp card padding).
3. **Friendly serif for warmth, clean sans for clarity.** `Newsreader` for headings, `Plus Jakarta Sans` for body. (Drop-in fallbacks listed below — do not ship Roboto/system defaults if Google Fonts is available.)
4. **Plain language.** Replace tech-speak ("Get Ready to Start Beta", "Accessibility for Beta") with conversational copy ("Get started", "Let Beta read the grocery app's screen").
5. **State must be visible.** Permissions show a clear ✓ / current / pending state, not a wall of identical grey boxes.
6. **You-in-control reassurance is a feature, not a footnote.** The "Beta stops at the cart" guarantee gets its own sage-green card with a checkmark, not just plain body text.

---

## 1. Design tokens

### 1.1 Colors — **replace** `app/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Legacy (keep — referenced by mipmap/launcher) -->
    <color name="purple_200">#FFBB86FC</color>
    <color name="purple_500">#FF6200EE</color>
    <color name="purple_700">#FF3700B3</color>
    <color name="teal_200">#FF03DAC5</color>
    <color name="teal_700">#FF018786</color>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>

    <!-- Beta v2 tokens — warm cream + terracotta + sage -->
    <color name="beta_background">#FFFAF7F2</color>          <!-- warm cream page bg -->
    <color name="beta_surface">#FFFFFFFF</color>             <!-- white cards -->
    <color name="beta_surface_alt">#FFF2EDE3</color>         <!-- sand chip / divider rows -->
    <color name="beta_line">#FFE6E0D3</color>                <!-- hairline borders -->

    <color name="beta_text_primary">#FF1F2433</color>        <!-- deep navy-charcoal -->
    <color name="beta_text_secondary">#FF6B6862</color>      <!-- warm gray -->

    <color name="beta_primary">#FFC2410C</color>             <!-- terracotta CTA -->
    <color name="beta_primary_pressed">#FFA8380A</color>     <!-- pressed state -->
    <color name="beta_primary_soft">#FFFBE8DC</color>        <!-- terracotta tint chip -->
    <color name="beta_on_primary">#FFFFFFFF</color>

    <color name="beta_secondary">#FFF2EDE3</color>           <!-- legacy alias → sand -->
    <color name="beta_success">#FF3F6B4F</color>             <!-- sage green text -->
    <color name="beta_success_bg">#FFE7EFE7</color>          <!-- sage tint card -->

    <color name="beta_amber">#FF9A5B0E</color>               <!-- "in progress" text -->
    <color name="beta_amber_bg">#FFFFF0DC</color>            <!-- "in progress" chip -->
</resources>
```

> Existing token names (`beta_background`, `beta_surface`, `beta_primary`, `beta_secondary`, `beta_text_primary`, `beta_text_secondary`, `beta_success`) are kept so any other XML referencing them still compiles. The colors are remapped.

### 1.2 Typography — Google Fonts via Downloadable Fonts

Beta currently has no custom fonts. Add `Newsreader` (serif) and `Plus Jakarta Sans` (sans) using AndroidX downloadable fonts so we don't ship TTFs.

**Create** `app/src/main/res/values/font_certs.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="com_google_android_gms_fonts_certs">
        <item>@array/com_google_android_gms_fonts_certs_dev</item>
        <item>@array/com_google_android_gms_fonts_certs_prod</item>
    </array>
    <string-array name="com_google_android_gms_fonts_certs_dev">
        <item>MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/TGFy0PTP6DHThvbbR24kT9ixcOd9W+EaBPWW+wPPKQmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpoxLuXX+08U7h7QEsK0OUmShFa/cl5UWPIozceeh9vR1rsuO28V+nlmmWsRsX5OFj0KfvCfVA1XobIodVSusy18LXCdSdW7Yk8ToTm/MVyMghCwLi+0LJvJVZB1bnzJyM7N4ImTKWaJ6n3Ws/UWNV4angjJlebnFvWmHJv6GLzn7t/BVOaaCQ4ezAVtSDpa6BftRzCXg+irYsmpr4mNB9BAQ8AAAA=</item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod">
        <item>MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsZ24w3sk+5qoNwBuCXLkSv7P83zTLfV2YgKfXWmKkRSEa8YfL4SI3+m3+aXkW1QGq77OPmpHwY1U7iA4PJj4yWvP+jJDfMTwcAjL+pjPnvVsanvAY9w/U/T/4Oj8sKr8DBJoTpZpWGl52pVwQk6f7VbghWVm/jiTzlEPdM1iY3SbLh/lc8MX+8mIqGAQ8AAAA=</item>
    </string-array>
</resources>
```

**Create** `app/src/main/res/font/newsreader.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:app="http://schemas.android.com/apk/res-auto"
    app:fontProviderAuthority="com.google.android.gms.fonts"
    app:fontProviderPackage="com.google.android.gms"
    app:fontProviderQuery="name=Newsreader&amp;weight=500"
    app:fontProviderCerts="@array/com_google_android_gms_fonts_certs">
</font-family>
```

**Create** `app/src/main/res/font/jakarta.xml` (regular) **and** `app/src/main/res/font/jakarta_semibold.xml` (semi-bold):

```xml
<!-- jakarta.xml -->
<font-family xmlns:app="http://schemas.android.com/apk/res-auto"
    app:fontProviderAuthority="com.google.android.gms.fonts"
    app:fontProviderPackage="com.google.android.gms"
    app:fontProviderQuery="name=Plus+Jakarta+Sans&amp;weight=400"
    app:fontProviderCerts="@array/com_google_android_gms_fonts_certs">
</font-family>
```

```xml
<!-- jakarta_semibold.xml -->
<font-family xmlns:app="http://schemas.android.com/apk/res-auto"
    app:fontProviderAuthority="com.google.android.gms.fonts"
    app:fontProviderPackage="com.google.android.gms"
    app:fontProviderQuery="name=Plus+Jakarta+Sans&amp;weight=600"
    app:fontProviderCerts="@array/com_google_android_gms_fonts_certs">
</font-family>
```

**Add to** `app/src/main/AndroidManifest.xml` (inside `<application>`, if not already present):

```xml
<meta-data
    android:name="preloaded_fonts"
    android:resource="@array/preloaded_fonts" />
```

**Create** `app/src/main/res/values/preloaded_fonts.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="preloaded_fonts" translatable="false">
        <item>@font/newsreader</item>
        <item>@font/jakarta</item>
        <item>@font/jakarta_semibold</item>
    </array>
</resources>
```

> Fallback if downloadable fonts are blocked: ship bundled `.ttf` files in `app/src/main/res/font/` and switch the font-family XML to `<font app:font="@font/newsreader_500" app:fontWeight="500" app:fontStyle="normal"/>` entries.

### 1.3 Type styles — **add to** `app/src/main/res/values/styles.xml`

Append (don't replace existing AppTheme):

```xml
<style name="Beta.Display" parent="android:TextAppearance">
    <item name="android:fontFamily">@font/newsreader</item>
    <item name="android:textColor">@color/beta_text_primary</item>
    <item name="android:textSize">34sp</item>
    <item name="android:lineSpacingMultiplier">1.05</item>
    <item name="android:letterSpacing">-0.01</item>
</style>

<style name="Beta.Title" parent="android:TextAppearance">
    <item name="android:fontFamily">@font/newsreader</item>
    <item name="android:textColor">@color/beta_text_primary</item>
    <item name="android:textSize">22sp</item>
</style>

<style name="Beta.Body" parent="android:TextAppearance">
    <item name="android:fontFamily">@font/jakarta</item>
    <item name="android:textColor">@color/beta_text_primary</item>
    <item name="android:textSize">17sp</item>
    <item name="android:lineSpacingExtra">3dp</item>
</style>

<style name="Beta.BodySoft" parent="Beta.Body">
    <item name="android:textColor">@color/beta_text_secondary</item>
</style>

<style name="Beta.Eyebrow" parent="android:TextAppearance">
    <item name="android:fontFamily">@font/jakarta_semibold</item>
    <item name="android:textColor">@color/beta_primary</item>
    <item name="android:textSize">13sp</item>
    <item name="android:letterSpacing">0.12</item>
    <item name="android:textAllCaps">true</item>
</style>
```

### 1.4 Shape drawables — create these in `app/src/main/res/drawable/`

**`beta_card.xml`** — white rounded card:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_surface"/>
    <stroke android:width="1.5dp" android:color="@color/beta_line"/>
    <corners android:radius="18dp"/>
</shape>
```

**`beta_card_soft.xml`** — sage reassurance card:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_success_bg"/>
    <corners android:radius="16dp"/>
</shape>
```

**`beta_btn_primary.xml`** — terracotta CTA with pressed state:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/beta_primary_pressed"/>
            <corners android:radius="18dp"/>
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/beta_primary"/>
            <corners android:radius="18dp"/>
        </shape>
    </item>
</selector>
```

**`beta_btn_secondary.xml`** — outlined neutral button:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_surface"/>
    <stroke android:width="1.5dp" android:color="@color/beta_line"/>
    <corners android:radius="18dp"/>
</shape>
```

**`beta_pill_amber.xml`** — status chip (in progress):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_amber_bg"/>
    <corners android:radius="999dp"/>
</shape>
```

**`beta_pill_sage.xml`** — status chip (ready):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_success_bg"/>
    <corners android:radius="999dp"/>
</shape>
```

**`beta_step_done.xml`** — sage circle (✓ done step):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_success"/>
    <size android:width="36dp" android:height="36dp"/>
</shape>
```

**`beta_step_current.xml`** — terracotta circle (current step):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/beta_primary"/>
    <size android:width="36dp" android:height="36dp"/>
</shape>
```

**`beta_step_pending.xml`** — outlined circle (pending step):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@android:color/transparent"/>
    <stroke android:width="1.5dp" android:color="@color/beta_line"/>
    <size android:width="36dp" android:height="36dp"/>
</shape>
```

**`beta_app_chip.xml`** — sand chip for "Works with Blinkit / Swiggy Instamart":
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/beta_surface_alt"/>
    <corners android:radius="999dp"/>
</shape>
```

---

## 2. Copy changes — **replace** strings in `app/src/main/res/values/strings.xml`

Keep all existing `name` attributes (they're referenced from layout + Kotlin). Replace **values** only:

```xml
<string name="main_title">Hi. Let\'s make grocery ordering easier.</string>
<string name="main_subtitle">Just tell Beta what you need. It will find your items and build your cart. You decide when to pay.</string>

<string name="main_primary_action">Get started</string>
<string name="main_primary_action_description">Set up Beta so it can help you order</string>
<string name="main_primary_note">Takes about two minutes. We\'ll walk you through it.</string>

<string name="main_status_ready">Ready to start</string>

<string name="setup_heading">A few quick permissions</string>
<string name="setup_accessibility">Let Beta read the grocery app\'s screen.</string>
<string name="setup_overlay">Let Beta show its helper on top.</string>
<string name="setup_screen_capture">Let Beta see the page you\'re on.</string>
<string name="setup_microphone">Allow microphone — only for voice orders.</string>

<string name="reassurance_heading">You stay in control</string>
<string name="reassurance_body">Beta builds your cart, then stops. It never pays or places the order. You always review and finish in the grocery app.</string>

<string name="more_options_heading">More ways to use Beta</string>
<string name="text_recognition_action">Read text on the screen</string>
<string name="voice_order">Speak your order</string>

<string name="feedback_label">Tell us how it went</string>
<string name="feedback_hint">What worked? What got stuck?</string>
<string name="feedback_include_logs">Include diagnostic logs</string>
<string name="feedback_worked">It worked</string>
<string name="feedback_issue">Something went wrong</string>
```

**Add** new strings for the redesign:

```xml
<string name="works_with_label">Works with</string>
<string name="works_with_blinkit">Blinkit</string>
<string name="works_with_instamart">Swiggy Instamart</string>
<string name="status_ready_pill">Ready</string>
<string name="step_label_format">Step %1$d of %2$d</string>
```

> **Do NOT touch** any string name not listed above (e.g. `automation_disclosure_*`, `accessibility_setup_*`, `voice_listening`, etc.) — they're used in dialogs and we're keeping those flows.

---

## 3. Layout — **replace** `app/src/main/res/layout/activity_main.xml`

Critical: **every** `@+id/...` in the original must still exist in the new layout. The activity does `findViewById` for: `captureScreenButton`, `textRecognitionButton`, `voiceOrderButton`, `feedbackMessageInput`, `includeLogsCheckbox`, `feedbackWorkedButton`, `feedbackIssueButton`. Additional IDs in the original (`mainTitle`, `mainSubtitle`, `text_agent_status`, `mainPrimaryNote`, `feedbackLabel`, `testAutomatedActionButton`) should remain too — they are referenced elsewhere or are useful handles.

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/beta_background"
    android:fillViewport="true"
    android:overScrollMode="never">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingHorizontal="24dp"
        android:paddingTop="32dp"
        android:paddingBottom="40dp">

        <!-- ─── Wordmark row ─────────────────────────────── -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:layout_marginBottom="28dp">

            <TextView
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:background="@drawable/beta_btn_primary"
                android:gravity="center"
                android:text="β"
                android:textColor="@color/beta_on_primary"
                android:fontFamily="@font/newsreader"
                android:textSize="26sp"/>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"
                android:fontFamily="@font/newsreader"
                android:textColor="@color/beta_text_primary"
                android:textSize="22sp"
                android:text="@string/app_name"/>
        </LinearLayout>

        <!-- ─── Hero ─────────────────────────────────────── -->
        <TextView
            android:id="@+id/mainTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="@font/newsreader"
            android:text="@string/main_title"
            android:textColor="@color/beta_text_primary"
            android:textSize="34sp"
            android:lineSpacingMultiplier="1.05"
            android:letterSpacing="-0.01"/>

        <TextView
            android:id="@+id/mainSubtitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:fontFamily="@font/jakarta"
            android:text="@string/main_subtitle"
            android:textColor="@color/beta_text_secondary"
            android:textSize="18sp"
            android:lineSpacingExtra="3dp"/>

        <!-- ─── Primary CTA ──────────────────────────────── -->
        <Button
            android:id="@+id/captureScreenButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="28dp"
            android:minHeight="72dp"
            android:background="@drawable/beta_btn_primary"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/main_primary_action"
            android:textAllCaps="false"
            android:textColor="@color/beta_on_primary"
            android:textSize="20sp"
            android:stateListAnimator="@null"
            android:contentDescription="@string/main_primary_action_description"/>

        <!-- ─── Status pill ──────────────────────────────── -->
        <TextView
            android:id="@+id/text_agent_status"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:paddingHorizontal="16dp"
            android:paddingVertical="10dp"
            android:background="@drawable/beta_pill_sage"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/main_status_ready"
            android:textColor="@color/beta_success"
            android:textSize="14sp"/>

        <TextView
            android:id="@+id/mainPrimaryNote"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:fontFamily="@font/jakarta"
            android:text="@string/main_primary_note"
            android:textColor="@color/beta_text_secondary"
            android:textSize="15sp"/>

        <!-- ─── Works with: Blinkit + Swiggy Instamart ──── -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:padding="18dp"
            android:background="@drawable/beta_card"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="@font/jakarta_semibold"
                android:text="@string/works_with_label"
                android:textAllCaps="true"
                android:letterSpacing="0.1"
                android:textColor="@color/beta_text_secondary"
                android:textSize="13sp"/>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:paddingHorizontal="14dp"
                    android:paddingVertical="10dp"
                    android:background="@drawable/beta_app_chip"
                    android:fontFamily="@font/jakarta_semibold"
                    android:text="@string/works_with_blinkit"
                    android:textColor="@color/beta_text_primary"
                    android:textSize="15sp"
                    android:drawableStart="@drawable/beta_dot_blinkit"
                    android:drawablePadding="8dp"/>

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="10dp"
                    android:paddingHorizontal="14dp"
                    android:paddingVertical="10dp"
                    android:background="@drawable/beta_app_chip"
                    android:fontFamily="@font/jakarta_semibold"
                    android:text="@string/works_with_instamart"
                    android:textColor="@color/beta_text_primary"
                    android:textSize="15sp"
                    android:drawableStart="@drawable/beta_dot_instamart"
                    android:drawablePadding="8dp"/>
            </LinearLayout>
        </LinearLayout>

        <!-- ─── Setup checklist ──────────────────────────── -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:fontFamily="@font/newsreader"
            android:text="@string/setup_heading"
            android:textColor="@color/beta_text_primary"
            android:textSize="24sp"/>

        <!-- Reusable step row template — duplicate 4× with different content -->
        <include layout="@layout/include_setup_step"
            android:id="@+id/setupStepAccessibility"/>
        <include layout="@layout/include_setup_step"
            android:id="@+id/setupStepOverlay"/>
        <include layout="@layout/include_setup_step"
            android:id="@+id/setupStepScreenCapture"/>
        <include layout="@layout/include_setup_step"
            android:id="@+id/setupStepMicrophone"/>

        <!-- (Codex: see §3.1 below for include_setup_step.xml. Set the
             title/body/state per step from MainActivity in onResume so it
             reflects real permission state.) -->

        <!-- ─── Reassurance card (sage) ──────────────────── -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="28dp"
            android:padding="20dp"
            android:background="@drawable/beta_card_soft"
            android:orientation="horizontal">

            <TextView
                android:layout_width="32dp"
                android:layout_height="32dp"
                android:background="@drawable/beta_step_done"
                android:gravity="center"
                android:text="✓"
                android:textColor="@color/beta_on_primary"
                android:textStyle="bold"
                android:textSize="17sp"/>

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="14dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:fontFamily="@font/jakarta_semibold"
                    android:text="@string/reassurance_heading"
                    android:textColor="@color/beta_success"
                    android:textSize="17sp"/>

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:fontFamily="@font/jakarta"
                    android:text="@string/reassurance_body"
                    android:textColor="@color/beta_success"
                    android:textSize="15sp"
                    android:lineSpacingExtra="2dp"/>
            </LinearLayout>
        </LinearLayout>

        <!-- ─── Optional help (text recognition, voice) ─── -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:fontFamily="@font/newsreader"
            android:text="@string/more_options_heading"
            android:textColor="@color/beta_text_primary"
            android:textSize="22sp"/>

        <Button
            android:id="@+id/voiceOrderButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:minHeight="64dp"
            android:background="@drawable/beta_btn_secondary"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/voice_order"
            android:textAllCaps="false"
            android:textColor="@color/beta_text_primary"
            android:textSize="17sp"
            android:stateListAnimator="@null"
            android:drawableStart="@android:drawable/ic_btn_speak_now"
            android:drawablePadding="14dp"
            android:contentDescription="@string/voice_order"/>

        <Button
            android:id="@+id/textRecognitionButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:minHeight="64dp"
            android:background="@drawable/beta_btn_secondary"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/text_recognition_action"
            android:textAllCaps="false"
            android:textColor="@color/beta_text_primary"
            android:textSize="17sp"
            android:stateListAnimator="@null"
            android:contentDescription="@string/text_recognition_action"/>

        <Button
            android:id="@+id/testAutomatedActionButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:minHeight="56dp"
            android:visibility="gone"
            android:text="Test Automated Actions"
            android:contentDescription="button_test_automated_actions"/>

        <!-- ─── Feedback ─────────────────────────────────── -->
        <TextView
            android:id="@+id/feedbackLabel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:fontFamily="@font/newsreader"
            android:text="@string/feedback_label"
            android:textColor="@color/beta_text_primary"
            android:textSize="22sp"/>

        <EditText
            android:id="@+id/feedbackMessageInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:padding="16dp"
            android:background="@drawable/beta_btn_secondary"
            android:fontFamily="@font/jakarta"
            android:contentDescription="feedback_message_input"
            android:hint="@string/feedback_hint"
            android:minLines="3"
            android:gravity="top"
            android:inputType="textMultiLine"
            android:textColor="@color/beta_text_primary"
            android:textColorHint="@color/beta_text_secondary"
            android:textSize="16sp"/>

        <CheckBox
            android:id="@+id/includeLogsCheckbox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:fontFamily="@font/jakarta"
            android:text="@string/feedback_include_logs"
            android:textColor="@color/beta_text_primary"
            android:textSize="15sp"
            android:buttonTint="@color/beta_primary"/>

        <Button
            android:id="@+id/feedbackWorkedButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            android:minHeight="56dp"
            android:background="@drawable/beta_btn_secondary"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/feedback_worked"
            android:textAllCaps="false"
            android:textColor="@color/beta_text_primary"
            android:textSize="16sp"
            android:stateListAnimator="@null"/>

        <Button
            android:id="@+id/feedbackIssueButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:minHeight="56dp"
            android:background="@drawable/beta_btn_secondary"
            android:fontFamily="@font/jakarta_semibold"
            android:text="@string/feedback_issue"
            android:textAllCaps="false"
            android:textColor="@color/beta_text_primary"
            android:textSize="16sp"
            android:stateListAnimator="@null"/>
    </LinearLayout>
</ScrollView>
```

### 3.1 Setup step row — **create** `app/src/main/res/layout/include_setup_step.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="12dp"
    android:padding="18dp"
    android:background="@drawable/beta_btn_secondary"
    android:orientation="horizontal"
    android:gravity="top">

    <TextView
        android:id="@+id/stepBadge"
        android:layout_width="36dp"
        android:layout_height="36dp"
        android:background="@drawable/beta_step_pending"
        android:gravity="center"
        android:fontFamily="@font/jakarta_semibold"
        android:textColor="@color/beta_text_secondary"
        android:textSize="17sp"
        android:text="1"/>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="14dp"
        android:layout_marginTop="4dp"
        android:orientation="vertical">

        <TextView
            android:id="@+id/stepTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="@font/jakarta_semibold"
            android:textColor="@color/beta_text_primary"
            android:textSize="18sp"
            android:text="Step title"/>

        <TextView
            android:id="@+id/stepBody"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:fontFamily="@font/jakarta"
            android:textColor="@color/beta_text_secondary"
            android:textSize="15sp"
            android:text="Step body"/>
    </LinearLayout>
</LinearLayout>
```

### 3.2 Brand dot drawables for "Works with" chips

**`drawable/beta_dot_blinkit.xml`** (Blinkit yellow):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#F8CB46"/>
    <size android:width="14dp" android:height="14dp"/>
</shape>
```

**`drawable/beta_dot_instamart.xml`** (Swiggy orange):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#FC8019"/>
    <size android:width="14dp" android:height="14dp"/>
</shape>
```

> These are abstract brand-color dots, not the trademarked logos — safe to ship.

---

## 4. Minimal Kotlin changes in `MainActivity.kt`

The activity must populate the four `<include>` step rows. Add this in `onCreate` after the existing `findViewById` block, and call it again from `onResume()` so state refreshes when the user returns from Settings.

```kotlin
private data class SetupStep(
    val containerId: Int,
    val titleRes: Int,
    val bodyRes: Int,
    val isDone: () -> Boolean
)

private val setupSteps by lazy {
    listOf(
        SetupStep(R.id.setupStepAccessibility, R.string.setup_accessibility,
            R.string.setup_accessibility_body, ::isBetaAccessibilityEnabled),
        SetupStep(R.id.setupStepScreenCapture, R.string.setup_screen_capture,
            R.string.setup_screen_capture_body) { isCapturing },
        SetupStep(R.id.setupStepOverlay, R.string.setup_overlay,
            R.string.setup_overlay_body) { Settings.canDrawOverlays(this) },
        SetupStep(R.id.setupStepMicrophone, R.string.setup_microphone,
            R.string.setup_microphone_body) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
    )
}

private fun renderSetupSteps() {
    var currentAssigned = false
    setupSteps.forEachIndexed { i, step ->
        val row = findViewById<View>(step.containerId)
        val badge = row.findViewById<TextView>(R.id.stepBadge)
        val title = row.findViewById<TextView>(R.id.stepTitle)
        val body  = row.findViewById<TextView>(R.id.stepBody)
        title.setText(step.titleRes)
        body.setText(step.bodyRes)

        val done = step.isDone()
        when {
            done -> {
                badge.background = ContextCompat.getDrawable(this, R.drawable.beta_step_done)
                badge.text = "✓"
                badge.setTextColor(ContextCompat.getColor(this, R.color.beta_on_primary))
                row.alpha = 0.7f
            }
            !currentAssigned -> {
                currentAssigned = true
                badge.background = ContextCompat.getDrawable(this, R.drawable.beta_step_current)
                badge.text = (i + 1).toString()
                badge.setTextColor(ContextCompat.getColor(this, R.color.beta_on_primary))
                row.alpha = 1f
                row.background = ContextCompat.getDrawable(this, R.drawable.beta_card)
            }
            else -> {
                badge.background = ContextCompat.getDrawable(this, R.drawable.beta_step_pending)
                badge.text = (i + 1).toString()
                badge.setTextColor(ContextCompat.getColor(this, R.color.beta_text_secondary))
                row.alpha = 1f
            }
        }
    }
}

override fun onResume() {
    super.onResume()
    renderSetupSteps()
}
```

Call `renderSetupSteps()` at the end of `onCreate` as well. Add `View` and `TextView` imports if not already present.

**Add strings** for the step body texts in `strings.xml`:

```xml
<string name="setup_accessibility_body">So Beta can see your grocery app.</string>
<string name="setup_overlay_body">So Beta can show its helper on top.</string>
<string name="setup_screen_capture_body">Beta only looks when you ask it to.</string>
<string name="setup_microphone_body">Optional — needed only for voice orders.</string>
```

Also update the status pill in `onResume`:

```kotlin
val statusPill = findViewById<TextView>(R.id.text_agent_status)
if (isCapturing) {
    statusPill.setBackgroundResource(R.drawable.beta_pill_amber)
    statusPill.setTextColor(ContextCompat.getColor(this, R.color.beta_amber))
    statusPill.text = "Running"
} else {
    statusPill.setBackgroundResource(R.drawable.beta_pill_sage)
    statusPill.setTextColor(ContextCompat.getColor(this, R.color.beta_success))
    statusPill.setText(R.string.main_status_ready)
}
```

---

## 5. Theme cleanup

In `app/src/main/res/values/themes.xml` add or set on the existing `AppTheme`:

```xml
<item name="android:windowBackground">@color/beta_background</item>
<item name="android:statusBarColor">@color/beta_background</item>
<item name="android:windowLightStatusBar">true</item>
<item name="android:navigationBarColor">@color/beta_background</item>
<item name="android:windowLightNavigationBar">true</item>
```

This makes the status bar blend into the cream background instead of dropping a default blue strip on top.

---

## 6. Acceptance checklist

A successful redesign delivery must satisfy ALL of:

- [ ] App builds and installs with no XML / resource errors.
- [ ] `MainActivity` opens on a warm cream background (no white-blue Android default).
- [ ] Hero title renders in `Newsreader` serif at 34sp.
- [ ] Body text renders in `Plus Jakarta Sans` at ≥17sp.
- [ ] Primary CTA "Get started" is full-width, terracotta (`#C2410C`), 72dp tall, with white text and a visible pressed state.
- [ ] Status pill is sage-green and rounded (`Ready`), changes to amber `Running` when capture is active.
- [ ] "Works with Blinkit · Swiggy Instamart" card is visible with two coloured-dot chips.
- [ ] Setup checklist shows 4 rows; granted permissions show a sage ✓ and reduced opacity; the first ungranted one is highlighted with a terracotta border and filled badge.
- [ ] Reassurance card has sage background, ✓ badge, and the "Beta builds your cart, then stops…" body.
- [ ] All original view IDs still resolve via `findViewById`; existing click handlers and feedback flow are unchanged.
- [ ] Tested at system text scale 100% **and** 130% — no clipping, no text overlap, all touch targets ≥ 48dp.
- [ ] Tested with TalkBack — every interactive element announces a meaningful label.

---

## 7. Out of scope (do not change)

- `MainActivity.kt` business logic, permission flows, intent handling, voice recognition setup.
- `MyAccessibilityService.kt`, `ScreenCaptureService.kt`, `BackendProcessing.kt`, or any other Kotlin file.
- The Compose theme files in `ui/theme/` — those aren't reached by `MainActivity` (it uses `setContentView(R.layout.activity_main)`, the XML path). Leave them alone.
- Dialog content (`automation_disclosure_*`, `accessibility_setup_*`) — keep current text.
- `TextRecognitionActivity` layout — separate task.

---

## 8. Reference

A visual mock of the redesign (welcome, setup, home, voice listening, cart review) is in the chat — `Beta UI Redesign.html`. The first three screens (welcome, setup, home/ready-state of MainActivity) correspond directly to what this spec implements. Screens 4 and 5 (voice listening, cart review) are forward-looking and not part of this round.
