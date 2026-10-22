# Reglas de R8 para el release.

# --- ML Kit (OCR on-device) -------------------------------------------------
# El modelo de reconocimiento de texto se carga por reflexión.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# --- Modelos de reconocimiento de texto -------------------------------------
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

# --- La app -----------------------------------------------------------------
# Los servicios se declaran en el manifiesto, R8 ya los conserva, pero el
# receptor de la notificación se referencia desde el código.
-keep class com.axelestrada.indriveinspector.overlay.StopAllReceiver { *; }

# --- Compose ----------------------------------------------------------------
# Las reglas de Compose vienen en sus propias librerías; no hace falta nada.

# --- Depuración -------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
