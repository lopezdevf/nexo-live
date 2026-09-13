# Reglas R8 específicas de la app

# RootEncoder registra en logcat los comandos RTMP completos, y el comando «publish» lleva la clave
# de emisión. En las versiones publicadas se eliminan los registros informativos de todas las
# bibliotecas; los avisos y errores se conservan.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
