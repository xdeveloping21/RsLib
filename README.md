# RsLib 1.2.0

Librería base de los plugins Rs de RsDevelopment para Paper 1.20 a 1.21.x (Java 21).

RsLib es un plugin que se instala en el servidor (`plugins/RsLib-<versión>.jar`) y que los
plugins Rs declaran como dependencia (`depend: [RsLib]`). Ofrece:

- Arranque y apagado ordenado (`RsPlugin`, contexto `rs()`).
- Archivos YAML gestionados que se siembran y se fusionan con el jar sin perder lo editado.
- Mensajes en `lang/messages_<idioma>.yml` con prefijo, listas, títulos y action bar.
- Texto con códigos `&`, hexadecimal, MiniMessage y las etiquetas `[center]`, `[rgb]` y `[small]`.
- Comandos con subcomandos, argumentos tipados, autocompletado, ayuda, alias desde el config y
  prioridad sobre otros plugins.
- Tareas, depuración, recarga y PlaceholderAPI opcional, con expansiones propias.
- Base de datos SQLite o MySQL (`rs().db()`): tablas, upserts, migraciones versionadas y caché
  por jugador.
- Cooldowns en memoria o guardados en la base de datos.
- Ítems: constructor de ítems, bloque de apariencia por jugador, ítems físicos registrados
  (`RsSpec.items(...)`, `rs().items()`) con acciones, recetas, reglas de muerte y
  serialización.
- Listas de acciones (`[message]`, `[console]`, `[open]`, `[next-page]`, ...) y de requisitos
  (`rs().actions()`), con acciones propias del plugin.
- Preguntas por chat con tiempo límite y cancelación (`rs().inputs()`).
- Dinero por Vault, comandos de consola o un sistema propio del plugin (`rs().economy()`),
  tops que se actualizan en segundo plano (`rs().leaderboards()`) y teletransportes con espera
  que se cancelan al moverse o recibir daño (`RsSpec.teleports()`, `rs().teleports()`).
- Copias del estado de un jugador en disco (`rs().snapshots()`): inventario, cofre de ender,
  vida, experiencia, efectos, modo de juego y ubicación, para modo staff o arenas; sobreviven a
  un reinicio o una caída y nunca se aplican dos veces.
- Menús desde `guis/*.yml` (`RsSpec.guis()`, `rs().guis()`): un inventario por jugador,
  plantillas, regiones, paginación con flechas, ofertas de ítems del jugador y protección
  contra robo de los ítems del menú.
- Barras de jefe por id con temporizador (`rs().bossbars()`), hologramas de texto que no se
  guardan en el mundo y vuelven al cargar su chunk (`rs().holograms()`; no tocan los de SnLib) y
  mensajes a webhooks de Discord en segundo plano, respetando sus límites (`rs().discord()`).
- Regiones cúbicas y varita de selección con partículas (`rs().selections()`), y eventos propios:
  equipar armadura, cambiar de chunk caminando y completar una selección.
- Tareas a horas del reloj en el hilo principal (`rs().cron()`, por ejemplo `"0 4 * * *"` o
  `"daily 04:00"`), con recuperación opcional de una ejecución perdida con el servidor apagado.
- Expresiones cron, duraciones, números, sonidos, cabezas con textura, nombres de jugadores,
  inventario, ubicaciones y experiencia.
- Avisos de versiones nuevas desde rsdevelopment.dev (`RsSpec.updates()`), y RsLib se
  actualiza sola (`auto-update` en su config.yml); además avisa en la consola y a quien tenga
  `rslib.admin.update` (también al entrar) cuando sale una versión nueva, aunque la
  actualización automática esté apagada. Su propia depuración se activa con `debug`
  en ese mismo archivo o con `/rslib debug`, y `update-configs: false` deja de agregar claves
  nuevas a los .yml de todos los plugins (solo las informa en la consola).

## Compilar

```
mvn install
```

Genera `target/RsLib-<versión>.jar` y deja `com.rs:rslib` en el repositorio Maven local para
compilar los plugins Rs:

```xml
<dependency>
    <groupId>com.rs</groupId>
    <artifactId>rslib</artifactId>
    <version>1.2.0</version>
    <scope>provided</scope>
</dependency>
```

## Uso mínimo

```java
public final class MiPlugin extends RsPlugin {

    @Override
    protected int requiredApiLevel() {
        return RsApi.LEVEL;
    }

    @Override
    protected RsSpec buildSpec() {
        return RsSpec.builder().config("config.yml").lang().build();
    }

    @Override
    protected void onInnerEnable() {
        rs().commands().root("miplugin")
                .permission("miplugin.admin")
                .description("Administración")
                .aliasesFromConfig()
                .register();
    }
}
```

## En Velocity

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

Autor: Raul Sanhueza (RsDevelopment) · https://rsdevelopment.dev

## Ofuscar un plugin que usa RsLib

`docs/rslib-consumer-rules.pro` tiene las reglas de ProGuard que un plugin ofuscado necesita
para seguir funcionando con RsLib: conserva los métodos que RsLib llama por nombre
(`requiredApiLevel`, `buildSpec`, `onPreEnable`, `onInnerEnable`, `onInnerDisable`), los
`@EventHandler`, los eventos, los enums que se leen por nombre, el paquete `.api` y lo que
Velocity busca. RsLib no viaja dentro del plugin, así que no se ofusca con él.

## Historial de versiones

- **1.2.0:** `/rslib` igual al `/snlib` documentado: subcomandos `plugins`, `integrations`
  (los enganches opcionales de cada plugin, activos o no), `iteminfo` (las etiquetas PDC del ítem
  en la mano) y `help`, con los textos de SnLib en `version`, `reload` y `debug`; sin argumento
  muestra la ayuda. `/rslib update` muestra el estado del actualizador y no revisa si
  `auto-update.enabled` es false. Aviso de versión nueva de RsLib en la consola y al entrar,
  sin usar `/rslib update`; una versión mayor nueva se informa en la consola. Al recargar, un
  `interval-hours` distinto rearma el temporizador. Versión y nivel de API en el manifiesto del
  jar (`RsLib-Version`, `RsLib-Api-Level`). Permisos nuevos `rslib.admin.plugins`,
  `rslib.admin.integrations` y `rslib.admin.iteminfo`; `rslib.admin` ya no incluye
  `rslib.admin.debug`, que se da aparte, como en SnLib. Nivel de API 2.
  Además, cada módulo revisado contra la documentación de SnLib y ajustado a ella: texto
  (null entra, null sale; la eñe en versalitas), mensajes (las claves `rslib.*` se agregan
  aunque `update-configs` sea false, una action bar simple no corta una fija, aviso de
  `<click>`/`<hover>` perdidos), PlaceholderAPI (avisa que espera a que se instale), menús
  (carpetas extra sembradas desde el jar y recargadas por espacio de nombres, `strict-clicks`
  solo con listas de acciones, un espacio nunca es una tecla), ítems (salto final en el lore,
  desgaste y raíz `items:`; `COMMAND_ONLY` sigue impidiendo equipar a mano y no toca el
  crafteo, como SnLib), comandos (ayuda
  del subcomando por defecto, `1,500` = 1500, duraciones con espacios, textos de la consola),
  base de datos, depuración, cooldowns, versión del servidor desconocida, Discord (valores
  vacíos se omiten), cron (una expresión inválida avisa en vez de fallar), teletransportes
  (`request(jugador, lugar)` es instantáneo) y avisos de actualización. También revisado
  contra sus páginas para administradores: archivos YAML (las tabulaciones se corrigen sin
  reescribir el archivo, un archivo roto se guarda como `<nombre>.backup-N` con el primer número
  libre, avisos con el prefijo `[update-configs]`), arranque del plugin (aviso de nivel de API y
  errores al habilitar con los textos de SnLib), menús (`update-interval` de una plantilla no se
  programa, aviso de menú vacío), apariencia de ítems (textura rota = cabeza normal, avisos de
  `damage` y `glow`) y base de datos (`socket-timeout-seconds` de 0 a 3600).
- **1.1.0:** regiones con varita de selección (`rs().selections()`, textos y valores como
  SnLib), eventos propios (armadura, cambio de chunk, selección completa), base para Velocity
  en el mismo jar, tareas a horas del reloj (`rs().cron()`), depuración propia de RsLib
  (`debug` en su config.yml y `/rslib debug`), `/rslib reload` sin plugin para su config.yml,
  `update-configs` y reglas de ProGuard para plugins ofuscados. Nivel de API 2.
- **1.0.0:** primera versión: núcleo, YAML, mensajes, texto, comandos, base de datos,
  cooldowns, PlaceholderAPI, menús, ítems, acciones, economía, tops, teletransportes, copias de
  jugador, barras de jefe, hologramas, Discord y actualizador propio. Nivel de API 1.
