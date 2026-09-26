# Evidencia de ofertas para Android

Aplicación Android local que observa la pantalla de **Uber Driver** mediante un servicio de accesibilidad. Cuando detecta una oferta visible, guarda:

- fecha y hora;
- texto accesible mostrado por Uber Driver;
- captura PNG de la pantalla, cuando Android permite tomarla;
- huellas SHA-256 del texto y de la imagen;
- un registro exportable en CSV.

No solicita permiso de Internet: las evidencias permanecen en el dispositivo hasta que el usuario decide compartirlas o exportarlas.

## Requisitos

- Android 11 (API 30) o posterior.
- Android Studio con JDK 17 para compilar localmente.
- Uber Driver instalado.

## Compilar en Android Studio

1. Descomprime el proyecto.
2. En Android Studio, selecciona **Open** y abre la carpeta `UberOfferEvidence`.
3. Espera a que termine la sincronización de Gradle.
4. Selecciona **Build > Build APK(s)**.
5. Instala el APK generado en tu teléfono Android.

## Compilar en GitHub

1. Crea un repositorio vacío en **github.com**.
2. Sube el contenido del proyecto al repositorio, desde la web de GitHub o con `git push`, usando `main` como rama principal.
3. Espera a que termine el workflow **Compilar APK Android** en la pestaña **Actions**.
4. Abre la ejecución terminada y descarga el artefacto **evidencia-ofertas-debug-apk**; dentro estará `app-debug.apk`.

## Configuración en el teléfono

1. Abre **Evidencia de ofertas**.
2. Pulsa **Activar captura automática**.
3. En Ajustes de accesibilidad, activa **Capturar ofertas de Uber Driver**.
4. Vuelve a Uber Driver. Mantén visible cada oferta hasta que la app termine de leerla y capturarla.
5. Regresa a esta app para revisar registros, compartir una captura o exportar el CSV.

La notificación **Captura de ofertas activa** incluye **Capturar ahora** para guardar manualmente el contenido visible de Uber Driver si la detección automática no reconoce una oferta.

## Botón flotante

Al abrir Uber Driver aparece un botón flotante (círculo) sobre la pantalla. Al tocarlo:

1. Guarda la evidencia de la oferta visible (captura + datos + huellas).
2. Toca automáticamente el botón **Match**/**Accept** de Uber en un punto aleatorio dentro de su zona habitual (medida en un Galaxy S22 Ultra: x 25%–90%, y 85%–91% de la pantalla).

El botón se puede **arrastrar** a cualquier posición y en la app se ajustan su **tamaño** (32–96 dp) y **opacidad** (30–100%). Requiere el permiso **"Mostrar sobre otras apps"**, que se concede una vez con el botón **Permitir botón flotante sobre Uber** en la pantalla principal.

Para verificar si la app logra leer el texto de las ofertas (o si haría falta OCR), abre un registro tocándolo en la lista: el detalle muestra el texto extraído. Si aparece vacío, el texto no está expuesto.

## Cómo funciona

El servicio solo procesa ventanas cuyo nombre de paquete parece corresponder a Uber Driver. Busca texto típico de una oferta (por ejemplo, aceptar/rechazar, viaje, distancia e importe), evita duplicados recientes y usa la API de captura de pantalla del servicio de accesibilidad. Los PNG se guardan en la galería del teléfono, en la carpeta **Pictures/EvidenciaOfertas** (visible en Galería y en el explorador de archivos). Desde el detalle de cada registro se puede **compartir** su captura o **borrar** ese registro individual; el botón **📷 Capturas** abre una pantalla con todas las evidencias en lista (miniatura, fecha, resumen y botones de compartir/borrar por fila, como referencia visual rápida). El botón **Borrar todos los registros** sigue disponible para limpiar todo.

## Limitaciones importantes

- Uber puede cambiar su interfaz, ocultar texto a los servicios de accesibilidad o bloquear capturas. En esos casos puede quedar solo el registro de texto o no detectarse la oferta.
- Una captura y su huella SHA-256 ayudan a conservar el contenido registrado, pero por sí solas no garantizan que una autoridad las acepte como prueba.
- El APK generado es de depuración y no está pensado para publicarse en Google Play.
- Revisa las condiciones de Uber y la legislación aplicable antes de usar o compartir registros que contengan nombres, direcciones u otros datos personales.
- Esta app no está afiliada ni respaldada por Uber.

## Privacidad

El proyecto no declara permiso de Internet. El servicio de accesibilidad puede leer el contenido visible de Uber Driver porque esa función es necesaria para registrar ofertas; Android mostrará una advertencia antes de activarlo. Puedes desactivarlo en cualquier momento desde Ajustes > Accesibilidad.
