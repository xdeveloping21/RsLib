# RsLib

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
- Expresiones cron, duraciones, números, sonidos, cabezas con textura, nombres de jugadores,
  inventario, ubicaciones y experiencia.
- Avisos de versiones nuevas desde rsdevelopment.dev (`RsSpec.updates()`), y RsLib se
  actualiza sola (`auto-update` en su config.yml).

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
    <version>1.0.0</version>
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

Autor: Raul Sanhueza (RsDevelopment) · https://rsdevelopment.dev
