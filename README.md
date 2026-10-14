# RsLib

Librería base de los plugins Rs de RsDevelopment para Paper 1.21.x (Java 21).

RsLib es un plugin que se instala en el servidor (`plugins/RsLib-<versión>.jar`) y que los
plugins Rs declaran como dependencia (`depend: [RsLib]`). Ofrece:

- Arranque y apagado ordenado (`RsPlugin`, contexto `rs()`).
- Archivos YAML gestionados que se siembran y se fusionan con el jar sin perder lo editado.
- Mensajes en `lang/messages_<idioma>.yml` con prefijo, listas, títulos y action bar.
- Texto con códigos `&`, hexadecimal, MiniMessage y las etiquetas `[center]`, `[rgb]` y `[small]`.
- Comandos con subcomandos, argumentos tipados, autocompletado, ayuda, alias desde el config y
  prioridad sobre otros plugins.
- Tareas, depuración, recarga y PlaceholderAPI opcional.

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
