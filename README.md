# RsLib 2.1.0

[![CI](https://github.com/xdeveloping21/RsLib/actions/workflows/ci.yml/badge.svg)](https://github.com/xdeveloping21/RsLib/actions/workflows/ci.yml)
![Versión](https://img.shields.io/badge/versi%C3%B3n-2.1.0-blue)
![Paper](https://img.shields.io/badge/Paper-1.20%20a%201.21.x-informational)
![Velocity](https://img.shields.io/badge/Velocity-3.4-informational)
![Java](https://img.shields.io/badge/Java-21-orange)
![API](https://img.shields.io/badge/nivel%20de%20API-4-success)

**La librería base de los plugins de RsDevelopment: gratis, se instala una vez y se actualiza sola.**

RsLib es la librería sobre la que se construyen los plugins de RsDevelopment. Se instala como un
plugin más (`plugins/RsLib-<versión>.jar`) y los plugins que la usan la declaran como dependencia
(`depend: [RsLib]`), así que basta con tenerla una sola vez en el servidor. El mismo jar funciona
también como plugin de Velocity para los plugins de proxy.

No agrega funciones para los jugadores por sí sola: aporta la base común de configuración,
mensajes, comandos, menús, base de datos y más, para que todos los plugins se comporten igual.

- **Descarga:** https://rsdevelopment.dev/plugins/rslib
- **Documentación completa (ES/EN):** https://rsdevelopment.dev/docs/rslib
- **Soporte:** https://discord.gg/rsdevelopment

## Índice

- [Características](#características)
- [Requisitos](#requisitos)
- [Instalación en el servidor](#instalación-en-el-servidor)
- [Archivos](#archivos)
- [Comandos](#comandos)
- [Permisos](#permisos)
- [Actualizaciones](#actualizaciones)
- [Para desarrolladores](#para-desarrolladores)
  - [Instalar RsLib en Maven](#instalar-rslib-en-maven)
  - [plugin.yml](#pluginyml)
  - [Clase principal](#clase-principal)
  - [Módulos (RsSpec)](#módulos-rsspec)
  - [Qué ofrece rs()](#qué-ofrece-rs)
  - [Archivos del plugin](#archivos-del-plugin)
  - [Recarga](#recarga)
  - [Nivel de API y compatibilidad](#nivel-de-api-y-compatibilidad)
  - [En Velocity](#en-velocity)
- [Compilar desde el código](#compilar-desde-el-código)
- [Licencia](#licencia)
- [Historial de versiones](#historial-de-versiones)

## Características

| | |
|---|---|
| **Configuración que no se pierde** | Los archivos YAML se crean desde el jar y, al actualizar, reciben las claves nuevas sin borrar tus valores ni tus comentarios. Un archivo roto se guarda como `<nombre>.backup-N` y las tabulaciones se corrigen solas. |
| **Mensajes en tu idioma** | `lang/messages_<idioma>.yml` con prefijo, listas, títulos y action bar. Colores `&`, hexadecimales, MiniMessage y las etiquetas `[center]`, `[rgb]` y `[small]`. |
| **Comandos completos** | Subcomandos, argumentos tipados, autocompletado, ayuda paginada, alias desde el config y prioridad sobre comandos de otros plugins (por ejemplo, EssentialsX). |
| **Menús desde archivos** | Menús en `guis/*.yml` con plantillas, regiones, paginación, ofertas de ítems del jugador y protección contra el robo de sus ítems. |
| **Ítems físicos** | Ítems registrados con acciones al usarlos, recetas, durabilidad propia, efectos al llevarlos, reglas de muerte y serialización. |
| **Acciones y requisitos** | Listas como `[message]`, `[console]`, `[open]`, `[title]`, `[sound]`, `[next-page]`, `[delay]` o `[firework]`, y requisitos con `>=`, `!=`, `&&` y `||` que fallan cerrados si están mal escritos. |
| **SQLite o MySQL** | Tablas, upserts, migraciones versionadas, caché por jugador y cooldowns que pueden sobrevivir a un reinicio. |
| **Se actualiza sola** | Descarga la versión nueva desde rsdevelopment.dev, comprueba su SHA-256 y la deja lista para el próximo reinicio. |
| **Tareas a horas del reloj** | Tareas como `0 4 * * *` o `daily 04:00`, con recuperación opcional de una ejecución perdida con el servidor apagado. |
| **Dinero y fechas a tu manera** | Símbolo, decimales, separadores, abreviaturas como `$1.5M` y estilos de fecha para todos los plugins en un solo archivo, `formats.yml`. |
| **Recompensas que no se pierden** | Lo que no cabe en el inventario o se gana sin estar conectado se guarda y se entrega al entrar; los avisos de una venta o una subasta llegan aunque el jugador no esté. |
| **Herramientas para el staff** | Registro de auditoría con `/rslib logs`, cuentas alternas por hash de la IP con `/rslib alts` y exportar o importar la base de datos de un plugin con `/rslib db`. |
| **Preferencias de los jugadores** | Un menú común, `/rslib settings`, donde cada jugador ajusta las opciones de todos los plugins y silencia los anuncios que no quiere ver. |
| **Y además** | Barras de jefe con temporizador, hologramas de texto que no se guardan en el mundo y vuelven solos si un comando los borra, webhooks de Discord, tops que se actualizan en segundo plano, teletransportes con espera, regiones con eventos de entrar y salir, consultas a WorldGuard sin depender de él, bloques que responden a clics, preguntas por chat, copias del estado de un jugador, precios de venta y multiplicadores por permiso. |

## Requisitos

| | |
|---|---|
| Servidor | Paper 1.20 a 1.21.x, o un fork de Paper (no Folia) |
| Proxy (opcional) | Velocity 3.4 |
| Java | 21 o posterior |

Dependencias opcionales:

| Plugin | Para qué |
|---|---|
| PlaceholderAPI | Los placeholders de los plugins que la usan. |
| Vault | La economía de los plugins que la usan. |
| WorldGuard | Que los plugins que lo usan consulten sus regiones y flags. Sin él, todo queda fuera de cualquier región y cada flag lo permite. |

## Instalación en el servidor

1. Descarga el jar de RsLib desde https://rsdevelopment.dev/plugins/rslib y colócalo en la
   carpeta `plugins/` de tu servidor, junto a los plugins de RsDevelopment. Debe haber un solo
   jar de RsLib en `plugins/`.
2. Inicia o reinicia el servidor. Se crean `plugins/RsLib/config.yml` y `formats.yml` y, con la
   detección de cuentas alternas activada (por defecto), `database.db` y `accounts-salt.key`.
   Mantén `accounts-salt.key` en privado y no lo borres.
3. Comprueba que cargó con `/rslib version`: muestra la versión, el nivel de API y la versión
   del servidor. `/rslib plugins` lista los plugins que la usan.

## Archivos

| Archivo | Contenido |
|---|---|
| `plugins/RsLib/config.yml` | Idioma de RsLib, `update-configs`, depuración, actualizador, regiones, cuentas alternas, avisos y auditoría. |
| `plugins/RsLib/formats.yml` | Formatos de dinero, fechas y tiempos relativos que usan todos los plugins (`{x:money}`, `{x:date}`, `{x:relative}`). |
| `plugins/RsLib/database.db` | Base de datos propia de RsLib (cuentas alternas). |
| `plugins/RsLib/accounts-salt.key` | Sal del hash de las direcciones IP. Si se borra, las direcciones guardadas dejan de coincidir. |

<details>
<summary><code>config.yml</code> completo</summary>

```yaml
# RsLib settings.

# Language of RsLib's own texts: en (English) or es (Spanish). It is used for the answers of
# /rslib settings, logs, db and alts, the /rslib settings menu, and relative times
# ({key:relative}, "3d ago" / "hace 3 d") in texts that no plugin language covers. Each plugin
# keeps its own language. Applied again by /rslib reload.
lang: en

# Adds to the YAML files of every plugin the keys a new version brings (with their comments,
# keeping your values). false: nothing is added, the missing keys are only reported in the
# console. This file is always updated. A plugin whose config.yml has its own update-configs
# uses that value for its files instead.
update-configs: true

# RsLib's own debug output (also switched on and off with /rslib debug, no restart needed).
debug:
  # Master switch of the debug output.
  enabled: false
  # How much prints: OFF, INFO, DEBUG or TRACE.
  level: DEBUG
  # Only these categories (for example update); an empty list lets every one through.
  categories: []

# RsLib updates itself from rsdevelopment.dev. A new version is downloaded, checked
# and placed in plugins/; it applies on the next restart.
auto-update:
  # Master toggle of the self-updater; false means you update RsLib by hand. Either way, the
  # console and players with rslib.admin.update are told when a new version is out.
  enabled: true
  # Hours between checks; the first one runs 2 minutes after startup. Clamped to 1-168.
  interval-hours: 12
  # Only install versions within the installed major version. A version that crosses a
  # major (2.x when 1.x is installed) is only reported in the console, never installed on its own.
  same-major-only: true

# Regions registered by plugins (enter and leave events).
regions:
  # Ticks between the checks that catch a player moved without an event (a plugin moving them
  # by packet, a vehicle). 20 ticks are one second. Clamped to 1-200.
  check-interval-ticks: 5

# Alt-account detection (/rslib alts <player>, and for plugins rs().accounts()), on by default.
# When a player joins, RsLib stores a salted SHA-256 hash of their IP address, never the address
# itself, with their UUID, name and the time, in its own database: plugins/RsLib/database.db
# (SQLite). The salt is created once in plugins/RsLib/accounts-salt.key: keep it private and do
# not delete it, or the addresses recorded so far stop matching.
accounts:
  # true (the default): every join is recorded as described above. false: nothing is recorded,
  # and /rslib alts and plugins are told that detection is off.
  enabled: true
  # Records of an address not used for this many days are deleted (checked one minute after
  # startup and then once a day). At least 1.
  keep-days: 90

# Messages the Rs plugins leave for players (a sale, an expired auction...). A player who is
# online sees them at once; the rest are kept in each plugin's database and shown on join.
notices:
  # Days an unread message is kept before it is deleted unread. At least 1.
  keep-days: 30
  # Most unread messages one player can have from one plugin; newer ones are refused (the
  # console says so).
  max-per-player: 500
  # Seconds after joining before the waiting messages are shown, so they are not buried
  # under the join messages. 0 to 600.
  join-delay-seconds: 3

# Staff audit log of the Rs plugins (who did what to whom), read with /rslib logs. Each
# plugin keeps its entries in its own database.
audit:
  # Days an entry is kept before it is deleted. 0 keeps them for ever.
  keep-days: 30
  # Seconds between writes of what was recorded (1 to 300). Reading always writes first.
  flush-seconds: 10
```

</details>

La clave `update-configs` decide si RsLib agrega a los .yml de todos los plugins las claves que
trae una versión nueva (`false`: solo las informa en la consola). Un plugin que tenga su propio
`update-configs` arriba de su config.yml usa ese valor para sus archivos (config, menús y
mensajes).

## Comandos

`/rslib` sin argumentos muestra la ayuda.

| Comando | Permiso | Qué hace |
|---|---|---|
| `/rslib version` | `rslib.admin.version` | Muestra la versión de RsLib, su nivel de API y la versión del servidor. |
| `/rslib plugins` | `rslib.admin.plugins` | Lista los plugins que funcionan sobre RsLib, con su versión. |
| `/rslib integrations` | `rslib.admin.integrations` | Lista las conexiones de cada plugin con plugins opcionales (Vault, PlaceholderAPI y otros) y si están activas. |
| `/rslib iteminfo` | `rslib.admin.iteminfo` | Muestra las etiquetas de datos persistentes (PDC) del ítem en tu mano principal. Solo jugadores. |
| `/rslib update` | `rslib.admin.update` | Muestra el estado del actualizador y, si `auto-update.enabled` es `true`, busca una versión nueva en ese momento. |
| `/rslib reload [plugin]` | `rslib.admin.reload` | Sin plugin, recarga el `config.yml` y el `formats.yml` de RsLib. Con un plugin hecho con RsLib, recarga sus archivos. Nunca carga un jar nuevo. |
| `/rslib logs <jugador\|*> [plugin\|*] [página]` | `rslib.admin.logs` | Registro de auditoría de los plugins, 10 entradas por página, de la más reciente a la más antigua. |
| `/rslib debug` | `rslib.admin.debug` | Activa o desactiva la depuración propia de RsLib y guarda el estado en `config.yml`. |
| `/rslib db export <plugin>` | `rslib.admin.db` | Exporta toda la base de datos de un plugin (o la de `RsLib`) a un archivo `.rsdump.gz` en su carpeta. |
| `/rslib db import <plugin> <archivo> [--force]` | `rslib.admin.db` | Importa un archivo exportado, también de SQLite a MySQL o al revés. Sin `--force` se rechaza si alguna tabla ya tiene filas. Después hay que reiniciar. |
| `/rslib alts <jugador>` | `rslib.admin.alts` | Lista las cuentas que entraron desde alguna de las direcciones IP del jugador, con la última vez que se vieron. |
| `/rslib settings` | `rslib.settings` | Abre el menú con tus preferencias en todos los plugins. Solo jugadores. |
| `/rslib help` | ninguno | Lista los subcomandos que puedes usar. |

Además, cada plugin hecho con RsLib recibe el subcomando `/<comando> reload` (permiso
`<plugin>.admin.reload`) y, si lo declara, `/<comando> debug` (permiso `<plugin>.admin.debug`).

## Permisos

| Permiso | Por defecto | Qué otorga |
|---|---|---|
| `rslib.admin` | op | `version`, `plugins`, `integrations`, `iteminfo`, `update`, `reload`, `logs` y `alts`. No incluye `debug` ni `db`. |
| `rslib.admin.version` | op | `/rslib version`. |
| `rslib.admin.plugins` | op | `/rslib plugins`. |
| `rslib.admin.integrations` | op | `/rslib integrations`. |
| `rslib.admin.iteminfo` | op | `/rslib iteminfo`. |
| `rslib.admin.update` | op | `/rslib update` y los avisos de versión nueva de RsLib (consola y al entrar). |
| `rslib.admin.reload` | op | `/rslib reload`. |
| `rslib.admin.logs` | op | `/rslib logs`, el registro de auditoría de todos los plugins. |
| `rslib.admin.debug` | op | `/rslib debug`. Se da aparte. |
| `rslib.admin.db` | op | `/rslib db export` e `import`. Se da aparte. |
| `rslib.admin.alts` | op | `/rslib alts`. |
| `rslib.settings` | op | `/rslib settings`. Dáselo a tus jugadores (por ejemplo, al grupo `default`) para que lo abran. |
| `rslib.chat.bypass` | nadie | Lee todos los mensajes del chat y todos leen los suyos, digan lo que digan las reglas de chat de los plugins. |

## Actualizaciones

- **RsLib se actualiza sola** (`auto-update` en su config.yml): cada `interval-hours` busca una
  versión nueva en rsdevelopment.dev, la descarga, comprueba su SHA-256 y la deja en `plugins/`
  para el próximo reinicio. Una versión mayor nueva (por ejemplo, 3.x con 2.x instalada) solo se
  informa en la consola, nunca se instala sola. Si hay dos jars de RsLib en `plugins/`, el
  actualizador no toca nada.
- **Aviso de versión nueva:** aunque el actualizador esté apagado, la consola y quien tenga
  `rslib.admin.update` (también al entrar) reciben el aviso cuando sale una versión nueva.
- **Los plugins de RsDevelopment** avisan igual de sus propias versiones nuevas a quien tenga
  `<plugin>.admin.update`.

## Para desarrolladores

Todo lo que RsLib ofrece a un plugin cuelga de un único objeto de contexto, `Rs`, que se obtiene
con `rs()` desde la clase principal. La guía completa, con una página por módulo, está en
https://rsdevelopment.dev/docs/rslib/developers.

### Instalar RsLib en Maven

RsLib no se publica en un repositorio Maven público. Para compilar contra ella, ejecuta en la
carpeta de su código fuente:

```
mvn install
```

Esto genera `target/RsLib-<versión>.jar` (el jar que va al servidor) y deja `com.rs:rslib` en el
repositorio Maven local (`~/.m2`). En el `pom.xml` del plugin, con Java 21
(`maven.compiler.release` en `21`):

```xml
<dependency>
    <groupId>com.rs</groupId>
    <artifactId>rslib</artifactId>
    <version>2.1.0</version>
    <scope>provided</scope>
</dependency>
```

Usa siempre el alcance `provided`: RsLib ya está cargada en el servidor y no debe ir dentro del
jar del plugin.

### plugin.yml

```yaml
name: MyKits
version: '1.0.0'
main: com.example.mykits.MyKits
api-version: '1.20'
depend: [RsLib]
commands:
  mykits:
    description: Server kits.
permissions:
  mykits.use:
    default: true
  mykits.admin.reload:
    default: op
  mykits.admin.debug:
    default: op
  mykits.admin.update:
    default: op
```

`<plugin>.admin.reload` y `<plugin>.admin.debug` (con el nombre del plugin en minúsculas) son los
permisos de los subcomandos automáticos `reload` y `debug`; `<plugin>.admin.update` recibe los
avisos de versión nueva de `RsSpec.updates()`. Declara también los comandos raíz: RsLib puede
registrar uno que no esté en el `plugin.yml`, pero avisa en la consola.

### Clase principal

La clase principal extiende `RsPlugin` en lugar de `JavaPlugin`. `onEnable()` y `onDisable()` son
`final`: se rellenan los ganchos que RsLib llama en un orden fijo.

```java
package com.example.mykits;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.RsApi;
import com.rs.lib.RsPlugin;
import com.rs.lib.RsSpec;
import com.rs.lib.db.Schema;
import com.rs.lib.gui.Gui;

public final class MyKits extends RsPlugin {

    @Override
    protected int requiredApiLevel() {
        return RsApi.LEVEL;
    }

    @Override
    protected RsSpec buildSpec() {
        return RsSpec.builder()
                .config("config.yml")
                .lang()
                .guis()
                .db()
                .debugCommand()
                .updates()
                .build();
    }

    @Override
    protected void onInnerEnable() {
        rs().db().bootstrap(Schema.of("mykits_claims",
                "uuid VARCHAR(36) NOT NULL",
                "kit VARCHAR(64) NOT NULL",
                "claimed_at BIGINT NOT NULL",
                "PRIMARY KEY (uuid, kit)")).join();

        rs().commands().root("mykits")
                .permission("mykits.use")
                .description("Server kits")
                .aliasesFromConfig()
                .sub("open").description("Open the kit menu")
                    .executes(ctx -> openMenu(ctx.player()))
                .and()
                .register();

        rs().reload().register(() -> getLogger().info("Kits reloaded"));
    }

    @Override
    protected void onInnerDisable() {
        // Every module is still alive here: save what you keep in memory.
    }

    private void openMenu(Player player) {
        Gui menu = rs().guis().get("kits");
        if (menu == null) {
            rs().lang().send(player, "menu-missing", Ph.of("menu", "kits"));
            return;
        }
        menu.open(player);
    }
}
```

| Método | Obligatorio | Cuándo se llama |
|---|---|---|
| `requiredApiLevel()` | Sí | Antes de todo; devuelve `RsApi.LEVEL`. |
| `onPreEnable()` | No | Antes de que RsLib toque la carpeta del plugin. Devuelve `false` para detener el arranque. |
| `buildSpec()` | No | Declara los módulos. Por defecto, `RsSpec.empty()`. |
| `onInnerEnable()` | Sí | El arranque propio del plugin, con `rs()` listo. Si lanza una excepción, RsLib la registra y desactiva el plugin. |
| `onInnerDisable()` | No | El guardado final; todos los módulos siguen activos. |

`rs()` devuelve `null` en el constructor, en `onLoad()` y en `onPreEnable()`.

### Módulos (RsSpec)

Un módulo que no se declara no se crea, y su acceso en `rs()` lanza una
`UnsupportedOperationException` que nombra la llamada que falta.

| Llamada | Qué activa |
|---|---|
| `config("config.yml")` | Configuración principal, sembrada desde el jar y fusionada con las claves nuevas en cada arranque (`rs().yml()`). |
| `lang()` | Mensajes en `lang/messages_<código>.yml`; el código es la clave `lang` del config (`rs().lang()`). |
| `guis()` | Cada `guis/*.yml` se carga al arrancar y al recargar (`rs().guis()`). Necesita `config(...)`. |
| `items("items.yml")` | Registra cada sección de ese archivo como ítem de `rs().items()`. Necesita `config(...)`. |
| `db()` | Base de datos SQLite o MySQL según la sección `database:` del config; sin ella, SQLite en `database.db` (`rs().db()`). |
| `teleports()` | Teletransportes con espera que se cancelan al moverse o recibir daño (`rs().teleports()`). |
| `rewards()` | Guarda las recompensas que no caben o que se ganan sin estar conectado. Necesita `db()`. |
| `debugCommand()` | Agrega el subcomando `debug` a cada comando raíz (`<plugin>.admin.debug`). |
| `commandPriority()` | Los comandos raíz del plugin les ganan el nombre a otros plugins que lo registraron antes, como EssentialsX. |
| `updates()` / `updates("id")` | Avisa de versiones nuevas desde rsdevelopment.dev (producto = nombre del plugin en minúsculas, o el id dado). |

### Qué ofrece rs()

| Módulo | Acceso | Guía |
|---|---|---|
| Archivos YAML gestionados | `rs().yml()` | [configuration-files](https://rsdevelopment.dev/docs/rslib/configuration-files) |
| Mensajes e idiomas | `rs().lang()` | [localization](https://rsdevelopment.dev/docs/rslib/localization) |
| Texto con colores y etiquetas | `RsText` (estático) | [text](https://rsdevelopment.dev/docs/rslib/text) |
| Comandos | `rs().commands()` | [commands-api](https://rsdevelopment.dev/docs/rslib/commands-api) |
| Menús | `rs().guis()` | [menus-api](https://rsdevelopment.dev/docs/rslib/menus-api) |
| Ítems físicos | `rs().items()` | [items-api](https://rsdevelopment.dev/docs/rslib/items-api) |
| Base de datos | `rs().db()` | [database-api](https://rsdevelopment.dev/docs/rslib/database-api) |
| PlaceholderAPI | `rs().papi()` | [placeholderapi](https://rsdevelopment.dev/docs/rslib/placeholderapi) |
| Acciones y requisitos | `rs().actions()` | [actions-api](https://rsdevelopment.dev/docs/rslib/actions-api) |
| Tareas y depuración | `rs().scheduler()`, `rs().debug()` | [debug-and-scheduler](https://rsdevelopment.dev/docs/rslib/debug-and-scheduler) |
| Cooldowns y dinero | `rs().cooldowns()`, `rs().economy()` | [cooldowns-economy-utils](https://rsdevelopment.dev/docs/rslib/cooldowns-economy-utils) |
| Recompensas, avisos y auditoría | `rs().rewards()`, `rs().notices()`, `rs().audit()` | [rewards-notices-audit](https://rsdevelopment.dev/docs/rslib/rewards-notices-audit) |
| Preferencias y chat | `rs().settings()`, `rs().chat()` | [settings-and-chat](https://rsdevelopment.dev/docs/rslib/settings-and-chat) |
| Precios, multiplicadores y formatos | `rs().prices()`, `rs().modifiers()`, `rs().formats()` | [money-prices-formats](https://rsdevelopment.dev/docs/rslib/money-prices-formats) |
| Regiones, bloques y cuentas alternas | `rs().regions()`, `rs().blocks()`, `rs().accounts()` | [regions-blocks-accounts](https://rsdevelopment.dev/docs/rslib/regions-blocks-accounts) |
| Barras, hologramas, tops, Discord y cron | `rs().bossbars()`, `rs().holograms()`, `rs().leaderboards()`, `rs().discord()`, `rs().cron()` | [bossbars-holograms-leaderboards-discord](https://rsdevelopment.dev/docs/rslib/bossbars-holograms-leaderboards-discord) |
| Teletransportes con espera | `rs().teleports()` | [teleports](https://rsdevelopment.dev/docs/rslib/teleports) |
| Varita de selección | `rs().selections()` | [region-selection](https://rsdevelopment.dev/docs/rslib/region-selection) |
| Avisos de versión nueva | `rs().updates()` | [update-checker](https://rsdevelopment.dev/docs/rslib/update-checker) |
| Preguntas por chat y copias de jugador | `rs().inputs()`, `rs().snapshots()` | [cooldowns-economy-utils](https://rsdevelopment.dev/docs/rslib/cooldowns-economy-utils) |
| Recarga | `rs().reload()`, `rs().reloadAll()` | [quickstart](https://rsdevelopment.dev/docs/rslib/quickstart) |

RsLib también lanza eventos propios (equipar armadura, cambiar de chunk caminando, completar una
selección): ver [custom-events](https://rsdevelopment.dev/docs/rslib/custom-events). El modelo de
hilos está en [threading-model](https://rsdevelopment.dev/docs/rslib/threading-model).

### Archivos del plugin

RsLib siembra en la carpeta del plugin los archivos que el jar trae en `src/main/resources` y, en
cada arranque y recarga, les agrega las claves nuevas sin borrar lo que el dueño editó.

| Archivo en el jar | Para qué sirve | Declaración |
|---|---|---|
| `config.yml` | Configuración principal | `config("config.yml")` |
| `lang/messages_en.yml` | Mensajes en inglés (base y respaldo) | `lang()` |
| `lang/messages_es.yml` | Traducción opcional | `lang()` |
| `guis/kits.yml` | Un menú por archivo | `guis()` |
| `items.yml` | Ítems físicos registrados al arrancar | `items("items.yml")` |

`config.yml`, con las claves que RsLib lee por su cuenta:

```yaml
# Master switch of the RsLib auto-updater for this plugin's managed files.
update-configs: true

# Language of the messages: lang/messages_<code>.yml
lang: en

command:
  # Extra names for /mykits. An empty list removes them all.
  aliases:
    - kits

# Optional. Without this section the plugin uses SQLite in database.db
database:
  type: sqlite
```

Para MySQL:

```yaml
database:
  type: mysql
  host: 10.0.0.5
  port: 3306
  database: mykits
  username: mykits
  password: "change-me"
  pool-size: 4
  ssl: false
  connect-timeout-seconds: 10
  socket-timeout-seconds: 30
```

`lang/messages_en.yml`:

```yaml
prefix: "&8[&6Kits&8] &7"

# One line: sent with the prefix.
kit-given: "&aYou received the kit &f{kit}&a."

# A list: each line is sent on its own, without the prefix.
kit-list:
  - "&6&lAvailable kits"
  - "&7- &fstarter"
  - "&7- &fvip"

# Shown as a title: title;subtitle;fade-in;stay;fade-out
welcome-title: "&6&lWelcome!;&7Have fun, {player};10;60;20"

# An empty text is not sent.
kit-cooldown: ""

# [noprefix] removes the prefix from this line only.
kit-info: "[noprefix]&7Use &f/kits &7to see every kit."

menu-missing: "&cThe menu &f{menu} &cdoes not exist."
```

`guis/kits.yml`, un menú con `layout` (cada letra es un ítem con esa `key`):

```yaml
title: "&8Server menu"
open-sound: "BLOCK_CHEST_OPEN 0.6 1.2"
update-interval: 40
strict-clicks: true
layout:
  - "fffffffff"
  - "fs w k pf"
  - "ffffxffff"
items:
  filler:
    material: GRAY_STAINED_GLASS_PANE
    display-name: " "
    key: f
  spawn:
    material: COMPASS
    display-name: "&aSpawn"
    lore:
      - "&7Teleports you to the spawn."
    key: s
    click-actions:
      - "[close]"
      - "[player] spawn"
  warps:
    material: ENDER_PEARL
    display-name: "&bWarps"
    key: w
    click-actions:
      - "[sound] UI_BUTTON_CLICK"
      - "[open] warps"
  kits:
    material: CHEST
    display-name: "&eKits"
    key: k
    click-requirements:
      - "%player_level% >= 5"
    click-actions:
      - "[player] kits"
  profile:
    material: PLAYER_HEAD
    skull-owner: "%player_name%"
    display-name: "&f%player_name%"
    lore:
      - "&7Level: &f%player_level%"
    key: p
  close:
    material: BARRIER
    display-name: "&cClose"
    key: x
    click-actions:
      - "[close]"
```

Las acciones disponibles son `[player]`, `[player-as-op]`, `[console]`, `[message]`,
`[broadcastmessage]`, `[actionbar]`, `[title]`, `[sound]`, `[close]`, `[open]`, `[connect]`,
`[next-page]`, `[previous-page]`, `[refresh-page]`, `[refresh-menu]`, `[set-page]`, `[particle]`,
`[potion]`, `[remove-item]`, `[delay]` y `[firework]`; cada plugin puede agregar las suyas. Los
detalles de menús, ítems y acciones están en
[menus](https://rsdevelopment.dev/docs/rslib/menus),
[physical-items](https://rsdevelopment.dev/docs/rslib/physical-items) y
[actions-and-requirements](https://rsdevelopment.dev/docs/rslib/actions-and-requirements).

### Recarga

`/<comando> reload` y `/rslib reload <plugin>` hacen lo mismo (`rs().reloadAll()`), en el hilo
principal y en este orden:

1. Vuelve a leer los archivos YAML (fusionando de nuevo las copias del jar).
2. Vuelve a cargar los mensajes.
3. Vuelve a registrar los ítems escritos en YAML y sus recetas.
4. Vuelve a cargar los menús. Los menús abiertos del plugin se cierran, salvo con
   `rs().guis().reopenAfterReload(true)`.
5. Vuelve a registrar los comandos, leyendo otra vez los alias de la configuración.
6. Llama a cada `Reloadable` registrado con `rs().reload().register(...)`, en orden.

Un paso que falla se registra en la consola y los demás se ejecutan igual. Las clases nunca se
recargan: un jar nuevo exige reiniciar el servidor.

### Nivel de API y compatibilidad

`RsApi.LEVEL` es una constante de compilación: al devolverla desde `requiredApiLevel()`, el plugin
recuerda contra qué RsLib se compiló. Si la RsLib instalada tiene un nivel menor, el plugin se
desactiva con un mensaje claro que pide actualizar RsLib, en lugar de fallar más tarde por un
método que no existe. El nivel sube en uno con cada versión que agrega API pública, y la API
pública no se elimina dentro de una misma versión mayor.

| Nivel de API | Versiones de RsLib |
|---|---|
| 5 | 2.1.0 y posteriores |
| 4 | 1.4.0 a 2.0.2 |
| 3 | 1.3.0 a 1.3.2 |
| 2 | 1.1.0 y 1.2.0 |
| 1 | 1.0.0 |

Paper 1.20 a 1.21.x comparte un solo jar: las diferencias entre versiones se resuelven en
tiempo de ejecución (`RsVersion`, `RsCompat`). El manifiesto del jar lleva `RsLib-Version` y
`RsLib-Api-Level`. Más detalles en
[compatibility-and-versioning](https://rsdevelopment.dev/docs/rslib/compatibility-and-versioning).

### En Velocity

El mismo `RsLib-<versión>.jar` es también un plugin de Velocity 3.4 (id `rslib`; pensado también para
Velocity-CTD). En el proxy solo se carga `com.rs.lib.velocity` con el texto y la fusión de
YAML, nunca una clase de Bukkit. Un plugin de proxy depende de `rslib` y usa `Rsv`: su
`config.yml` se crea desde el jar y se le añaden las claves que falten, sin tocar lo demás ni
los comentarios; tiene tareas (`scheduler()`), comandos (`command(...)`) y `color(...)`. No hay
mensajería entre servidores.

```java
@Plugin(id = "miproxy", dependencies = @Dependency(id = "rslib"))
public final class MiProxy {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private Rsv rsv;

    @Inject
    public MiProxy(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        rsv = Rsv.create(this, proxy, logger, dataDir);
        rsv.command("hub", new HubCommand(rsv), "lobby");
    }
}
```

## Compilar desde el código

Con Java 21 o posterior y Maven:

```
mvn install
```

Compila, ejecuta las pruebas y genera `target/RsLib-<versión>.jar`, además de dejar
`com.rs:rslib` en el repositorio Maven local. El CI de GitHub hace lo mismo en cada cambio de
`main` y corre los benchmarks.

## Licencia

RsLib es software propietario de Raul Sanhueza (RsDevelopment); el texto completo está en
[LICENSE](LICENSE). Cualquiera puede instalarla y usarla gratis, sin clave de licencia y en
cualquier servidor, para correr los plugins de RsDevelopment, gratuitos o de pago (sección 1A),
siempre que sea el jar sin modificar. Cada plugin de pago sigue necesitando su propia clave.

Autor: Raul Sanhueza (RsDevelopment) · https://rsdevelopment.dev · https://discord.gg/rsdevelopment

## Historial de versiones

- **2.1.0:** estilos de holograma. `HologramStyle` define todo el aspecto del texto: hacia
  dónde gira (`FIXED`, `VERTICAL`, `HORIZONTAL` o `CENTER`), sombra, verse a través de los
  bloques, fondo (`default`, `none`, `#RRGGBB`, `#AARRGGBB` o `R, G, B[, A]`), alineación,
  ancho de línea, opacidad del texto, escala y distancia de vista. Se da al crear
  (`holograms().spawn(id, location, lines, style)`) o después con `setStyle(id, style)`, y
  vuelve a aplicarse cada vez que el holograma reaparece (chunk recargado o entidad borrada);
  `setBillboard` y `setViewRange` siguen funcionando encima. `HologramStyle.read(section,
  fallback, warn)` lo lee desde yml: cada clave es opcional y un valor inválido conserva el
  anterior con un aviso que nombra la clave. `HologramStyle.DEFAULT` es el aspecto de siempre,
  así que los hologramas sin estilo se ven igual. Nivel de API 5: solo los plugins que usen
  estilos necesitan RsLib 2.1.0; los demás funcionan con solo cambiar el jar.
- **2.0.2:** los hologramas vuelven solos en el tick siguiente cuando un comando u otro plugin
  borra su entidad (por ejemplo `/kill @e[type=text_display]` o `/killall`). Si se borran
  más de 5 veces en 10 segundos, la consola lo avisa una vez y el holograma queda abajo hasta
  que cambien sus líneas o se vuelva a cargar su chunk. Sin cambios de API: los plugins no se
  recompilan, y los que traían un listener propio para esto pueden quitarlo.
- **2.0.1:** cabezas con `skull-owner` en servidores en modo offline. Si el dueño está
  conectado, se usa su perfil del juego, así que la skin que le puso el servidor o un plugin de
  skins (por ejemplo, SkinsRestorer) sale en la cabeza. Un UUID de modo offline sin skin ya no se
  busca en Mojang (no puede tener ninguna), y la consola ya no muestra `Could not find the skin of
  skull-owner ...` por cada jugador de un servidor offline. Sin cambios de API: los plugins no se
  recompilan.
- **2.0.0:** cambio incompatible: las marcas que RsLib guarda en los ítems pasan a llamarse
  `rslib_*` (`rslib_item_id`, `rslib_locked`, `rslib_no_drop`, `rslib_no_manual_equip`,
  `rslib_keep_on_death`, `rslib_obtain_via`, `rslib_durability`, `rslib_durability_line`, las
  recetas `<plugin>:rslib_recipe_<id>` y los modificadores de atributos `rslib:attr_*`). Se
  quitan las lecturas heredadas: los nombres de marca anteriores, el bloque de idioma anterior,
  las claves de mensajes con el prefijo anterior, la marca de sección extensible anterior y la
  importación de nombres de jugadores de otra librería. Se quitan las constantes públicas
  `GuiManager.LEGACY_ITEM_TAG`, `YamlUpdater.LEGACY_EXTENSIBLE_MARKER` y
  `YamlUpdater.LEGACY_EXTENSIBLE_ROOT_MARKER`. Nivel de API 4, sin cambios. Pasos para
  actualizar: instala `RsLib-2.0.0.jar` a mano (el actualizador no instala solo una versión
  mayor); los ítems de los plugins entregados antes de esta versión dejan de reconocerse y hay
  que volver a darlos; en los menús y archivos propios, la marca de sección extensible se
  escribe `# rs:extensible` (o `# rs:extensible-root`).
- **1.4.0:** `update-configs` por plugin: si el config.yml de un plugin tiene su propio
  `update-configs` arriba de todo, ese valor decide para todos sus archivos (config, menús y
  mensajes); sin la clave, sigue mandando el de RsLib. Los textos compartidos `rslib.*` se
  agregan siempre, como antes. Nivel de API 4.
- **1.3.2:** compilación sin avisos: las pruebas ya no muestran advertencias de javac, los
  mensajes que las pruebas provocan a propósito no salen en la consola y shade ya no avisa por
  los manifiestos repetidos de las librerías. Sin cambios en el código.
- **1.3.1:** compila con Java 25 en Maven: javac corre en un proceso aparte (`<fork>true</fork>`),
  porque el javac de Java 25 dentro de Maven fallaba con "this.hashes is null" al compilar las
  pruebas. Sin cambios en el código.
- **1.3.0:** novedades tomadas de la revisión de ExyliaLib, escritas desde cero: recompensas con
  entrega garantizada (lo que no cabe se guarda por defecto), avisos para después, registro de
  auditoría (`/rslib logs`), preferencias y canales silenciables (`/rslib settings`, solo con
  permiso), captura de chat y reglas de lectores, dinero sin conexión y transferencias con
  reembolso, precios de venta, multiplicadores por permiso, `formats.yml` con dinero, fechas y
  `:relative` en el idioma de cada plugin, placeholders con valor por defecto, `[delay]` y
  `[firework]`, cron con condiciones, regiones con entrar y salir, WorldGuard opcional, bloques
  con clic protegidos, cooldown visible en el ítem, asistente de preguntas, editor de listas,
  exportar e importar la base (`/rslib db`) y cuentas alternas (`/rslib alts`). Los textos de
  esos comandos se traducen con el `lang` nuevo del config.yml de RsLib. Arreglos: Discord ya
  no deja mencionar a `@everyone` ni a nadie salvo que el plugin lo permita, reintenta los
  errores del servidor de Discord y deja de usar un webhook borrado; una tarea repetitiva que
  falla se cancela. Rendimiento: un solo reloj para hologramas y menús, hologramas que no
  trabajan si nadie los ve, movimiento suave, placeholders compartidos en menús y benchmarks.
  Estructura: parte de la implementación pasó a paquetes `internal` y las clases de más de 1.000
  líneas se dividieron. CI en GitHub Actions. Nivel de API 3.
- **1.2.0:** `/rslib` con los subcomandos `plugins`, `integrations`
  (los enganches opcionales de cada plugin, activos o no), `iteminfo` (las etiquetas PDC del ítem
  en la mano) y `help`, con textos revisados en `version`, `reload` y `debug`; sin argumento
  muestra la ayuda. `/rslib update` muestra el estado del actualizador y no revisa si
  `auto-update.enabled` es false. Aviso de versión nueva de RsLib en la consola y al entrar,
  sin usar `/rslib update`; una versión mayor nueva se informa en la consola. Al recargar, un
  `interval-hours` distinto rearma el temporizador. Versión y nivel de API en el manifiesto del
  jar (`RsLib-Version`, `RsLib-Api-Level`). Permisos nuevos `rslib.admin.plugins`,
  `rslib.admin.integrations` y `rslib.admin.iteminfo`; `rslib.admin` ya no incluye
  `rslib.admin.debug`, que se da aparte. Nivel de API 2.
  Además, cada módulo revisado contra su documentación y ajustado a ella: texto
  (null entra, null sale; la eñe en versalitas), mensajes (las claves `rslib.*` se agregan
  aunque `update-configs` sea false, una action bar simple no corta una fija, aviso de
  `<click>`/`<hover>` perdidos), PlaceholderAPI (avisa que espera a que se instale), menús
  (carpetas extra sembradas desde el jar y recargadas por espacio de nombres, `strict-clicks`
  solo con listas de acciones, un espacio nunca es una tecla), ítems (salto final en el lore,
  desgaste y raíz `items:`; `COMMAND_ONLY` sigue impidiendo equipar a mano y no toca el
  crafteo), comandos (ayuda
  del subcomando por defecto, `1,500` = 1500, duraciones con espacios, textos de la consola),
  base de datos, depuración, cooldowns, versión del servidor desconocida, Discord (valores
  vacíos se omiten), cron (una expresión inválida avisa en vez de fallar), teletransportes
  (`request(jugador, lugar)` es instantáneo) y avisos de actualización. También revisado
  contra sus páginas para administradores: archivos YAML (las tabulaciones se corrigen sin
  reescribir el archivo, un archivo roto se guarda como `<nombre>.backup-N` con el primer número
  libre, avisos con el prefijo `[update-configs]`), arranque del plugin (aviso de nivel de API y
  errores al habilitar con textos claros), menús (`update-interval` de una plantilla no se
  programa, aviso de menú vacío), apariencia de ítems (textura rota = cabeza normal, avisos de
  `damage` y `glow`) y base de datos (`socket-timeout-seconds` de 0 a 3600). Por último, revisado
  contra las páginas de texto, comandos, formatos de valores, solución de problemas, archivos de
  idioma y acciones: los avisos de la consola usan textos revisados, el actualizador no toca
  nada si hay dos jars de RsLib en `plugins/`, las opciones de `[particle]` avisan cuando están
  mal escritas y las duraciones de los comandos se leen como en los archivos (palabras
  desconocidas se saltan y un número solo son segundos). Los requisitos mal escritos siguen
  fallando siempre y no hay semanas ni meses en las duraciones.
- **1.1.0:** regiones con varita de selección (`rs().selections()`), eventos propios (armadura, cambio de chunk, selección completa), base para Velocity
  en el mismo jar, tareas a horas del reloj (`rs().cron()`), depuración propia de RsLib
  (`debug` en su config.yml y `/rslib debug`), `/rslib reload` sin plugin para su config.yml
  y `update-configs`. Nivel de API 2.
- **1.0.0:** primera versión: núcleo, YAML, mensajes, texto, comandos, base de datos,
  cooldowns, PlaceholderAPI, menús, ítems, acciones, economía, tops, teletransportes, copias de
  jugador, barras de jefe, hologramas, Discord y actualizador propio. Nivel de API 1.
