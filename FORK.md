# Chiaki-Up Plus (Chiaki-Up+)

> Fork handheld-friendly de [Chiaki-Up](https://github.com/gameblabla/chiaki-up): mismo cliente de
> PS4/PS5 Remote Play, pensado para equipos cuyo mando no tiene un botón PS utilizable.
> El proyecto se llama **Chiaki-Up Plus** y la etiqueta del lanzador es **Chiaki-Up+**.

Fork de [gameblabla/chiaki-up](https://github.com/gameblabla/chiaki-up), el cliente
Android de PS4/PS5 Remote Play. Existe por una razón concreta: **en un handheld o
con un mando que no tiene botón PS utilizable no hay forma de abrir el menú de
PlayStation sin encender los controles en pantalla.**

El botón Home de esos equipos (Retroid, Anbernic, mandos genéricos) manda
`KEYCODE_HOME`, que Android intercepta y nunca llega a la app. Chiaki-Up solo
acepta `KEYCODE_BUTTON_C` y `KEYCODE_BUTTON_MODE` como PS, así que la única salida
era activar el overlay y tocar el botón en pantalla — que tapa media pantalla.

## Qué agrega este fork

Diferencia con upstream: **6 commits, ~250 líneas**. Nada más del código se toca,
para que el rebase sobre upstream siga siendo trivial.

1. **Start + Select = PS.** Aguantar los dos envía una pulsación de PS a la consola.
   Las pulsaciones sueltas de Options y Share se retienen 120 ms para no adelantarse:
   si en esa ventana aparece la otra tecla, gana la combinación. Un toque rápido de
   cualquiera de las dos que termine dentro de la ventana se envía igual, como
   pulsación corta de 60 ms, para que pausar con un toque siga funcionando. Sin
   overlay, sin apps de terceros, sin root.
2. **L1 + R1 + Start = estadísticas del stream.** Muestra fps decodificados, Mbps
   efectivos y % de pérdida de paquetes sobre la barra del stream. L1 y R1 salen al
   instante (retrasarlos se sentiría en cada pulsación de juego), así que el precio
   del atajo es un toque breve de L1+R1 que la consola sí ve antes de que abra el
   panel. Start sí se retiene, y mientras el combo esté armado no se envía nada.
3. **Nada más en el input.** Se probó también `Back = PS` (pulsación simple PS,
   doble salida) y se descartó: interceptar Back rompe el gesto de swipe-back de
   Android y deja al usuario sin salida natural de la sesión. Solo Start+Select.
4. **Bugs de empaquetado corregidos:** los `layout_behavior` de `activity_main.xml`
   usaban nombres relativos (rompía cualquier build con otro `applicationId`) y la
   autoridad del `FileProvider` estaba hardcodeada (impedía instalar al lado de otra
   copia con `INSTALL_FAILED_CONFLICTING_PROVIDER`).

El switch está en Ajustes y se lee en cada evento de tecla: cambiarlo aplica al
instante, sin reconectar el stream.

## Instalar

Hay dos sabores de compilación:

- **`standard`** → `com.gameblabla.chiaki`, reemplaza un Chiaki-Up instalado. Con
  firma distinta Android exige desinstalar primero la del Play Store; para no
  perder las consolas registradas, exportar ajustes antes e importarlos después.
- **`sideapk`** → `com.gameblabla.chiaki.pscombo`, se instala **al lado** del de
  Play Store, sin desinstalar nada. Es la opción recomendada para probar.

## Compilar

Requisitos: JDK 17, Android SDK con `platforms;android-35`, `build-tools;35.0.0`,
`ndk;29.0.14206865`, `cmake;3.22.1`, y `protoc` en el PATH
(v21.12 de protobuf sirve).

```bash
git clone --recursive <este-repo> && cd <este-repo>
git submodule update --init --recursive \
  third-party/nanopb third-party/jerasure third-party/gf-complete android/app/src/main/cpp/oboe
./scripts/prepare-build.sh          # parches para NDK 29 y protobuf >= 4

cd android
./gradlew assembleSideapkDebug -Parm64only   # o assembleStandardRelease
```

`-Parm64only` compila solo `arm64-v8a` (mucho más rápido; es lo que usan todos los
handhelds modernos). Sin esa bandera salen las cuatro ABIs.

### Firma

`android/local.properties` (no versionado):

```properties
sdk.dir=/ruta/al/Android/sdk
chiakiKeystore=/ruta/a/chiaki.jks
chiakiKeystorePW=...
chiakiKeyAlias=chiaki
chiakiKeyPW=...
```

Sin keystore el build usa la firma debug de Gradle y entonces **no** puede
actualizar una instalación anterior: cada APK exigiría desinstalar. Generar uno
propio y conservarlo:

```bash
keytool -genkeypair -keystore chiaki.jks -alias chiaki -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass PASS -keypass PASS -dname "CN=Chiaki, O=tu, C=CO"
```

### Si compilas desde un venv de Python

El generador de nanopb necesita `pkg_resources`. Si el `python3` del PATH no lo
tiene, añade a los `arguments` de CMake en `android/app/build.gradle`:

```
"-DPYTHON_EXECUTABLE=/usr/bin/python3"
```

## Sincronizar con upstream

`master` de este repo sigue a `gameblabla/chiaki-up`. Los cambios viven en commits
separados y con alcance mínimo, así que actualizar es:

```bash
git remote add upstream https://github.com/gameblabla/chiaki-up
git fetch upstream && git rebase upstream/master
```

## Licencia y créditos

AGPL-3.0, igual que upstream. Este fork **no es** un proyecto de Sony Interactive
Entertainment y no está avalado por ella. El crédito del cliente original es de
Florian Märkl, el port Android y su mantenimiento de gameblabla, y el protocolo y
las mejoras de red de chiaki-ng. Al distribuir un APK modificado, la AGPL obliga a
ofrecer el código correspondiente: este repositorio lo es.

Los dos primeros commits (nombres de clase en layouts, autoridad del FileProvider)
están pensados para poder mandarse como PR a upstream, y el tercero también; si
ellos lo toman, este fork puede desaparecer sin que nadie pierda la función.
