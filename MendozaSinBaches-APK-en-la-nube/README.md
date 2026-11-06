# Mendoza Sin Baches — prototipo Android 0.1

## Generar el APK sin instalar Android Studio
Este proyecto incluye un flujo de GitHub Actions para compilar el APK en la nube.

1. En GitHub, crea un repositorio nuevo llamado `MendozaSinBaches` (puede ser privado).
2. Descomprime este ZIP en tu computadora.
3. En el repositorio, elige **Add file > Upload files** y arrastra todos los archivos y carpetas descomprimidos. Asegúrate de incluir la carpeta oculta `.github/workflows/`.
4. Confirma la carga con **Commit changes**.
5. En el repositorio, abre la pestaña **Actions** y selecciona **Generar APK - Mendoza Sin Baches**.
6. Pulsa **Run workflow** si no se inició automáticamente y espera que termine con un tilde verde.
7. Abre la ejecución finalizada. En **Artifacts**, descarga `Mendoza-Sin-Baches-APK-debug`.
8. Descomprime ese archivo y encontrarás `app-debug.apk`. Envíalo a tu teléfono Android e instálalo. Android podría pedir permiso para instalar aplicaciones desde el navegador/gestor de archivos.

No necesitas instalar Android Studio en tu computadora. GitHub Actions realiza la compilación en sus servidores.

## Incluye
- Mapa OpenStreetMap centrado inicialmente en Mendoza Capital.
- Alta de reportes con tipo de daño, dirección, departamento, gravedad, descripción y foto opcional.
- Captura de coordenadas GPS con permiso del dispositivo.
- Listado y filtros por departamento y estado.
- Panel administrativo de demostración (PIN `2468`), actualización de estados, eliminación y exportación CSV.
- Datos guardados en el almacenamiento local del dispositivo.

## Limitaciones importantes
Esta es una **versión prototipo local**, no una plataforma pública lista para producción:
- Los reportes no se sincronizan entre teléfonos ni se envían a municipios.
- Los datos y fotos están en el almacenamiento local del dispositivo.
- El PIN de administración está dentro del código y **no es seguro**. Antes de publicar hay que implementar autenticación real y permisos en un servidor.
- El mapa necesita conexión a internet. El uso de fotos y GPS depende de los permisos de Android.
- Para una versión pública se recomienda API/backend, base de datos, almacenamiento de fotos, moderación, deduplicación, protección antiabuso, política de privacidad y HTTPS.

## Próximo paso recomendado
Conectar Firebase (Authentication para administradores, Firestore para reportes y Storage para fotografías) o un backend equivalente, con reglas de seguridad. Así todos los vecinos podrán ver los mismos reportes y solo administradores autorizados podrán cambiar estados.
