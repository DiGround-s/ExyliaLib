# AGENTS.md — ExyliaLib

Doctrina del proyecto. Lee esto antes de tocar código o de diseñar un módulo
nuevo. Si algo que vas a escribir contradice este documento, o el documento está
mal y hay que cambiarlo con un argumento, o el código está mal.

---

## Qué es ExyliaLib

Librería compartida de alto rendimiento para los plugins de Exylia.

- **No se shadea.** Vive una sola vez en el servidor como plugin, y los plugins
  la consumen con `compileOnly`. Una copia, una clase, una caché.
- **No es un plugin de features.** No añade comandos, ítems ni mecánicas de
  juego. Da infraestructura a otros plugins.
- **Es código abierto.** Terceros la leen. La API pública y su documentación son
  producto, no notas internas.

Repositorio: <https://github.com/DiGround-s/ExyliaLib>
Coordenada: `com.github.DiGround-s:ExyliaLib`

### Objetivo rector

Que el servidor procese lo menos posible. En concreto, y por orden de impacto
real:

1. **No bloquear el main thread.** Es el recurso escaso; ahí se pierde TPS.
2. **No pedirle al servidor estado que no necesita ser estado.** Cuando algo se
   puede resolver con un packet al cliente en vez de con entidades o bloques
   reales, se resuelve con packets (PacketEvents).
3. **No repetir trabajo.** Cachear en memoria antes que volver a consultar.

Lo que **no** es optimizar: microoptimizar rutas que ya corren fuera del main
thread, o añadir capas para ahorrar nanosegundos donde el coste real está en
I/O. Ver *Decisiones cerradas*.

---

## Stack

| | |
| --- | --- |
| Java | 21 |
| API | paper-api 1.21.4 (`compileOnly`) |
| Plataformas | Spigot, Paper, Purpur y **Folia**, desde un solo build |
| Packets | PacketEvents 2.13.0 (`compileOnly`) |
| Caché | Caffeine 3.2.4 |
| Build | Gradle, `java-library` + `maven-publish` |
| Tests | JUnit 5 |

PacketEvents y Caffeine son las últimas estables (verificado en CodeMC y Maven
Central). Caffeine no tiene rama 4.x: 3.x es la línea estable.

Ambas se instalan una sola vez en el servidor, así que la versión tiene que ser
la misma en todos los plugins de Exylia. Si subes una aquí, se sube en todos: no
queremos dos versiones de la misma librería conviviendo. Hoy los plugins están en
Caffeine 3.2.2, así que subirlos a 3.2.4 es una tarea pendiente, no algo ya
hecho.

---

## Reglas permanentes

### Tasks — siempre la API de ExyliaLib

**Nunca** `BukkitRunnable`, `Bukkit.getScheduler()`, `new Thread(...)`, ni un
`ExecutorService` propio. Todo pasa por `net.exylia.lib.task`.

```java
private TaskScheduler tasks;

@Override
public void onEnable() {
    this.tasks = Tasks.of(this);
}
```

Se elige método por **qué toca la task**, y eso es lo que hace al plugin
compatible con Folia sin ramificar por plataforma:

| Toca | Método |
| --- | --- |
| entidad o jugador | `runAtEntity(...)` |
| bloques, chunks, una posición | `runAtLocation(...)` |
| nada thread-bound (HTTP, DB, ficheros) | `runAsync(...)` |
| estado global del servidor | `run(...)` |

En Spigot y Paper todas las variantes no-async caen en el main thread, así que
elegir la correcta no cuesta nada y el plugin corre en Folia sin tocarlo.

Detalles que la librería ya resuelve, y que por tanto **no** debes reimplementar
en cada plugin: cancelación al deshabilitar, aislamiento de excepciones,
normalización de ticks, timers de entidad que paran solos, y `cancel()` seguro
desde cualquier hilo.

### Caché — siempre Caffeine

Para cualquier caché se usa **Caffeine**. Es la mejor opción disponible en JVM y
ya es la que usan los plugins de Exylia.

- No `HashMap` como caché. Un mapa sin política de expiración ni tope es una
  fuga de memoria con pasos extra.
- No Guava Cache: Caffeine es su sucesor, más rápido y con mejor política de
  desalojo (W-TinyLFU).
- Toda caché lleva **límite** (`maximumSize`) o **expiración**
  (`expireAfterWrite` / `expireAfterAccess`). Sin uno de los dos, no es caché.
- Datos por jugador: invalidar en quit, o expirar. Un jugador que se fue no debe
  seguir ocupando memoria.

### Packets antes que estado

Si el efecto solo tiene que verlo el cliente, es un packet, no una entidad real.
Hologramas, ítems de display, glow, previsualizaciones, efectos cosméticos: todo
eso como packet cuesta al servidor una fracción de lo que cuesta como entidad
tickeando.

Regla: si el servidor no necesita simular ese objeto, el servidor no debe
conocerlo.

### Base de datos

- Conexiones por **pool acotado** (HikariCP). Nunca abrir conexión por operación.
- Toda operación de DB fuera del main thread, con `runAsync`.
- El resultado vuelve al hilo correcto (`runAtEntity` / `runAtLocation`) antes de
  tocar nada del juego.
- Cachear con Caffeine para no ir a la DB en caliente. Agrupar escrituras en
  lote antes que una query por evento.

---

## Barra de calidad de un módulo

Un módulo entra en ExyliaLib solo si cumple todo esto:

1. **Resuelve un problema real y repetido** en varios plugins. No se añade
   infraestructura especulativa "por si acaso".
2. **API pequeña y obvia.** El consumidor debe acertar sin leer la
   implementación. Si hay que explicar el orden de las llamadas, la API está mal.
3. **Documentada en Javadoc**, en inglés, con ejemplo de uso en la clase de
   entrada y en cada método no trivial. Documenta el *porqué* y los contratos
   (hilos, nulabilidad, ciclo de vida), no lo que ya dice la firma.
4. **Funciona igual en Bukkit y Folia**, o declara explícitamente que no aplica.
5. **Sin fugas.** Nada queda vivo cuando el plugin consumidor se deshabilita.
6. **Aislamiento de clases.** Tipos específicos de una plataforma se confinan en
   una clase que solo se carga en esa plataforma. La librería debe cargar en
   Spigot puro.
7. **Con tests de comportamiento**, no solo de compilación.

### Estructura

```
net.exylia.lib
├── ExyliaLib          plugin runtime, solo ciclo de vida y limpieza
├── platform/          detección de plataforma
└── <modulo>/
    ├── API pública    interfaces y punto de entrada
    └── internal/      implementaciones — nadie fuera depende de esto
```

Todo lo que esté en `internal` es libre de cambiar sin aviso. Todo lo que esté
fuera es contrato público: romperlo obliga a subir versión mayor.

---

## Verificación antes de dar algo por terminado

No basta con que compile.

- `./gradlew build` — limpio, sin warnings ni notes.
- Tests que **realmente detectan fallos**: rompe la lógica a propósito y
  comprueba que el test correspondiente falla. Un test que nunca falla no prueba
  nada.
- **Consumo real**: publicar con `publishToMavenLocal` y compilar un plugin de
  prueba contra la librería usando la API nueva. Es donde se nota si la API es
  incómoda.
- **Inspección del artefacto** cuando toques algo sensible a la plataforma:

  ```bash
  # ninguna clase salvo la específica debe referenciar tipos de Folia
  for f in $(find . -name "*.class"); do
    javap -c -p "$f" | grep -q threadedregions && echo "$f"
  done
  ```

- API que solo existe en Paper (`getPluginMeta()`, etc.) no puede estar en rutas
  que se ejecuten en Spigot. Se usa la API portable aunque esté deprecada, y se
  documenta por qué.

---

## Decisiones cerradas

Documentadas para no volver a discutirlas sin datos nuevos.

### No añadimos un pool de threads propio para tasks

`runAsync` **ya es un pool**, verificado en el código del servidor:

```java
// CraftAsyncScheduler — Paper/Spigot
new ThreadPoolExecutor(4, Integer.MAX_VALUE, 30L, SECONDS, new SynchronousQueue<>(), ...)

// FoliaAsyncScheduler — Folia
new ThreadPoolExecutor(Math.max(4, availableProcessors() / 2), Integer.MAX_VALUE, ...)
```

Añadir otro pool encima no quita trabajo al servidor: añade una capa y nos hace
perder la cancelación automática al deshabilitar el plugin.

El riesgo real de ese diseño (`maximumPoolSize` ilimitado con `SynchronousQueue`,
es decir, threads sin tope) **no se arregla con más threads**, se arregla
acotando el recurso escaso en su punto: un *connection pool* (HikariCP) para la
base de datos. Limitar conexiones, no tareas.

### El versionado es inmutable

Un tag publicado en JitPack queda cacheado para siempre. Un cambio en una versión
ya publicada exige versión nueva; mover el tag no sirve.

---

## Estilo

- Código, nombres y Javadoc **en inglés**. Este documento y la comunicación de
  equipo, en español.
- Comentarios que expliquen **por qué**, nunca qué. Si el qué no se entiende, el
  problema es el nombre o la estructura, no la falta de comentario.
- Sin dependencias nuevas salvo necesidad demostrada. Cada una se instala en
  todos los servidores.
- Sin abstracción especulativa. Se añade cuando hay un segundo caso real, no
  cuando se imagina.
