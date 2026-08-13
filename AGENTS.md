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
| Comandos | Lamp 4.0.0-rc.17 (`compileOnly` + `libraries:` en plugin.yml) |
| Sidebars | scoreboard-library 2.8.1 (**shadeada y relocalizada**) |
| Caché | Caffeine 3.2.4 |
| Build | Gradle, `java-library` + `maven-publish` + `shadow` |
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

### Configs — siempre records, nunca YAML a mano

Un config se declara como `record` y se usa como `record`. No escribimos ficheros
YAML a mano ni buscamos valores por string en caliente.

```java
ConfigFile<Storage> storage = Configs.define(this, "storage", Storage.class).load();
int pool = storage.get().poolSize();
```

- **El YAML es salida, no entrada.** El fichero se genera desde el record, con
  sus comentarios. Editar el `.yml` del `resources/` a mano es la señal de que
  algo está mal.
- **Nada de `getInt(path)` en caliente.** Leer del snapshot es un acceso a campo;
  ir al `FileConfiguration` en cada evento es parseo repetido.
- **Renombrar una clave exige `Migration`.** Cambiar la anotación sin migración
  hace que el servidor pierda en silencio lo que el dueño había configurado.
- **Un typo del usuario nunca tumba el plugin.** Se reporta y se usa el default.
- Los comentarios de `@Comment` son el manual del dueño del servidor: explican
  qué cambia el valor, en qué unidad y en qué rango.

### Texto y color — siempre `Text`, siempre Components

Todo lo que ve un jugador pasa por `net.exylia.lib.text`. Nunca
`ChatColor`, ni `translateAlternateColorCodes`, ni concatenar `§`.

```java
Text.of("{primary}&lWELCOME").send(player);
```

- **Se devuelve `Component`, no `String`.** Un String legacy no puede llevar
  hover, click ni RGB fiable. `legacy()` existe solo para APIs viejas que aún lo
  exigen.
- **Colores por rol, no por hex.** Se escribe `{primary}`, no `<#8a51c4>`. Así el
  dueño del servidor recolorea todo desde `colors.yml`.
- **Valores que cambian van con `.with()`**, nunca concatenados. Concatenar
  rompe la caché y obliga a re-parsear en cada tick; `.with()` sustituye sobre el
  Component ya parseado.
- **Adventure es la del servidor.** Se compila contra la versión que trae
  `paper-api`, fijada con `resolutionStrategy`. Compilar contra una más nueva
  compila bien y luego revienta con `NoSuchMethodError` en producción.
- **Un mensaje puede pedir efectos con la notación de commons**
  (`[sound:X|1|1;particle:Y|20;center]texto`). Se mantiene **idéntica a
  ExyliaCommons** a propósito: migrar un plugin no debe obligar a reescribir
  los ficheros de mensajes.
  - La etiqueta es una instrucción, no texto: nunca llega a pantalla, a un log
    ni al nombre de un ítem.
  - Solo un `Player` recibe los efectos; una consola recibe el texto.
  - Un prefijo entre corchetes que no reconocemos se deja intacto
    (`[Server] Restarting`), y un `[` sin cerrar es texto.
  - Un efecto malformado se reporta y se salta: **el mensaje siempre llega**.
  - `Text` no reproduce nada por su cuenta: le pide a `Effects`, como
    cualquier plugin.
- **La vía de paquetes es una preferencia, nunca un requisito.** Si el registry
  de PacketEvents no conoce un nombre — o el classloader no ve PacketEvents —
  el efecto sale por la API de Bukkit. Un `false` del packet path significa
  "no conozco ese nombre", no "sonó".
- **Las claves de sonido no se derivan con reglas de strings.**
  `BLOCK_NOTE_BLOCK_PLING` es `block.note_block.pling` (guión bajo *dentro* de
  la clave) pero `ENTITY_PLAYER_LEVELUP` es `entity.player.levelup`. Se resuelve
  por el enum de Bukkit; inventar la clave mal la paga el cliente con silencio.
- **Una config se poda contra su record al cargar.** Clave que ningún campo
  declara se elimina y se reporta una vez, como hacía el modo estricto de
  commons. Las migraciones corren antes (todavía pueden leer el layout viejo) y
  `config-version` está reservado. Avisar sin borrar solo convierte cada clave
  retirada en una tradición del log de arranque.
- **Los valores sustituidos son literales por defecto, formateados a pedido.**
  `with()` inserta texto plano (lo que teclea un jugador no puede inyectar
  formato); `withFormatted()`/`forPlayerFormatted()` parsean el valor (un
  display name de config *es* su formato). Elegir mal en cualquiera de los dos
  sentidos es un bug visible en chat.
- **El aviso de placeholder desconocido distingue "sin dueño" de "sin valor".**
  Un resolver registrado que devuelve null no es un placeholder desconocido, y
  llamarlo así manda al autor a buscar un registro que existe.
- **El `Map` de `apply` da valores, no solo contexto.** Un resolver registrado
  gana siempre; el mapa se consulta cuando nadie posee el nombre. La necesidad
  más común al mandar un mensaje es "sustituye esto aquí", y durante un tiempo
  la firma obvia no hacía nada: el primer plugin migrado mandó `%class%` literal
  al chat de un servidor en vivo.
- **Un placeholder que no resuelve se reporta**, una vez por nombre. Fallar en
  silencio significa que el bug lo encuentra un jugador, no el desarrollador.
- **El prefijo es por plugin, no del servidor.** El registro de placeholders es
  un mapa plano por nombre: un `%prefix%` global haría que dos plugins se
  pelearan por él. `Text.of` lo deja intacto a propósito — texto que no dice de
  qué plugin viene no tiene prefijo que usar. Se sustituye antes de parsear y
  antes de centrar, porque lleva colores propios y su ancho cuenta.
- **Centrar se mide en píxeles, no en caracteres.** La fuente de Minecraft no
  es monoespaciada. La tabla de anchos es la de commons para que una línea
  centrada allí lo siga estando aquí; el formato (tags, códigos legacy,
  tokens de paleta) no ocupa, y la negrita suma un píxel por carácter.

### Placeholders — un solo tipo, registro por grupo

Todo placeholder se registra con `net.exylia.lib.placeholder`. No hay cuatro
tipos de resolver ni cuatro registros: hay **uno**.

```java
Placeholders.group(this, "clan")
        .add("name", r -> clans.of(r.requireViewer()).name())
        .add("top", r -> clans.leaderboard().at(r.arg(0, 1)))
        .register();
```

- **Un resolver es `(Request) -> Object`.** Si necesita jugador, argumentos o
  datos extra se ve en lo que lee del `Request`, no en una categoría elegida al
  registrar. Eso es lo que elimina registrar lo mismo cuatro veces.
- **El prefijo se declara una vez**, en el grupo. Y todo el grupo se libera solo
  cuando el plugin se deshabilita.
- **Formatear es del placeholder, no del plugin.** Se escribe
  `%eco_balance:comma%` en la config; no se formatea a mano en Java. Así el dueño
  del servidor controla la presentación.
- **Nunca devolver un valor vacío para decir "no hay".** Se devuelve `null`: el
  módulo aplica el fallback (`%clan_name|Sin clan%`) o deja el placeholder
  visible para que el typo se vea.
- **Un resolver que revienta no tumba nada.** Se reporta una vez y se trata como
  sin valor. Por eso un resolver reporta fallos devolviendo `null`, no lanzando.
- **`.async()` es una promesa, no una optimización.** Solo si el resolver no toca
  la API de Bukkit. Marcarlo mal revienta el servidor tarde y en otro sitio.
- **Línea que se repite, `compile()`.** Un scoreboard compila su plantilla una
  vez y la guarda: medido, 3.4x más rápido que pasar el string en cada tick.

### Efectos — siempre `Effects`, siempre configurables

Todo lo que ve u oye un jugador pasa por `net.exylia.lib.effect`. Nunca
`player.sendTitle`, ni `BossBar` de Bukkit, ni `spawnParticle` a mano.

- **El efecto se declara en config, no en Java.** El plugin dice *qué pasó*
  (`Effects.play(config.onWin(), player)`); el dueño decide si eso es un title,
  un sonido o fuegos. `EffectConfig` anida en el record del plugin.
- **El tiempo va en segundos con decimales.** `countdown(3.3)` es 3.3s reales, y
  `%time%` lo muestra como `3.3`. Nada de multiplicar por 20 a mano.
- **`%time%` es del efecto, no global.** Un timer pertenece a un efecto; si fuera
  un placeholder registrado, dos countdowns en pantalla mostrarían lo mismo.
- **Sin timer, el efecto es permanente** hasta que se pare. Y `onEnd` corre
  **exactamente una vez**, termine como termine.
- **Texto estático no programa task.** Si nada cambia, se dibuja una vez. Un bar
  permanente con texto fijo cuesta un packet, no un task por tick.
- **Fuegos artificiales son la excepción**: se spawnea y detona en el mismo tick.
  Todo lo demás es packet.

### Scoreboards — siempre `Scoreboards`, siempre configurables

Todo sidebar pasa por `net.exylia.lib.scoreboard`. Nunca el `Scoreboard` de
Bukkit, ni objetivos y teams a mano, ni una copia propia de scoreboard-library.

- **El board se declara en config, no en Java.** El plugin dice *a quién* mostrar
  *qué board* (`Scoreboards.show(this, player, config.ffa())`); el dueño escribe
  título, líneas e intervalo. `SidebarConfig` anida en el record del plugin.
- **El YML es el de ExyliaCommons.** Mismas claves (`enabled`, `title`, `lines`,
  `update.interval/smart/cache`) y misma librería por debajo, para que migrar un
  plugin de commons a lib no obligue al dueño a tocar su fichero.
- **Aquí el intervalo va en ticks**, no en segundos como el resto de la librería.
  Es una desviación consciente y acotada a esta sección: un `interval: 15`
  existente tiene que seguir significando 15 ticks.
- **Solo se envía lo que cambió.** El diff se hace sobre el string renderizado,
  antes de parsear y antes de tocar packets. Un board cuyos valores no se movieron
  cuesta comparar strings y cero packets.
- **Se parsea el crudo, no el resuelto.** El texto de la plantilla no cambia
  nunca, así que se parsea una vez y los valores se sustituyen sobre el
  Component. Medido: 26.8µs contra 4.2µs por línea cambiada.
- **Los boards se apilan por jugador.** Mostrar uno pausa el anterior, y al
  cerrarlo vuelve solo. Un board pausado no renderiza nada.
- **Un solo timer async mueve todos los boards**, con desfase por UUID para no
  concentrar los renders en el mismo tick.
- **Nada sobrevive a su dueño**: al salir el jugador, al deshabilitar el plugin, o
  al recargar la paleta (ahí se reenvía entero, porque el texto es el mismo pero
  lo que parsea no).

### Hologramas — siempre `Holograms`, siempre configurables

Todo objeto que flota pasa por `net.exylia.lib.hologram`. Nunca una entidad real
de Bukkit, ni un ArmorStand, ni un `TextDisplay` spawneado a mano.

- **Se declaran en config, no en Java.** El plugin dice *dónde* ponerlo
  (`Holograms.show(this, id, location, config.trophy())`); el dueño escribe
  líneas, tipo, colores y visibilidad. `HologramConfig` anida en el record.
- **El YML es el de ExyliaCommons.** Mismas claves que escribía
  `HologramTemplateSerializer`, excepto las que aquí no pintan nada (chunks,
  persistencia en disco: un holograma es solo packets, no es un archivo).
- **El intervalo va en ticks**, como el scoreboard. Otra desviación acotada
  para que los ficheros de commons sigan sirviendo.
- **Son packets o no son nada.** Si PacketEvents no está, `isSupported()` es
  `false` y todo sigue funcionando sin dibujar nada. No hay entidad real de
  fallback que tickee y ocupe un registro del servidor.
- **La visibilidad se comprueba cuatro veces por segundo** con distancia al
  cuadrado por jugador por holograma; solo se mandan packets al cruzar el borde.
- **Solo se envía lo que cambió.** Una línea sin placeholders nunca refresca.
  Una que sí, diffea y reenvía solo ella, no el holograma entero.
- **Moverse es teleport, no respawn.** Así un holograma que sigue a un jugador
  no parpadea. Y montarlo sobre una entidad (`attachTo`) ni siquiera manda
  packets mientras se mueve: lo mueve el cliente junto al vehículo.
- **Nada sobrevive a su dueño**: al deshabilitar el plugin, al salir el jugador,
  o al recargar la paleta (ahí se reenvía entero).

### Clientes modificados — siempre `Clients`, nunca ramificar

Todo lo que dependa de Lunar o Feather pasa por `net.exylia.lib.client`. Nunca
`Apollo.getPlayerManager()` ni `FeatherAPI` en un plugin.

- **El plugin nunca pregunta qué cliente lleva el jugador.** Dice lo que debería
  ver (`Clients.waypoints().show(...)`) y quien pueda pintarlo, lo pinta. Un
  jugador vanilla no es un caso especial: es una consulta a un mapa y nada más.
- **Cada cliente es un `ClientLink` y una línea en `ClientRegistry`.** Añadir uno
  nuevo no toca nada más. Lo que un cliente no sabe hacer se responde con
  `false` en su `supports`, no con una excepción.
- **Apollo y Feather se confinan a una clase cada uno**, igual que PacketEvents.
  Verificado en bytecode: solo `ApolloLink` y `FeatherLink` los nombran.
- **La detección se cachea por jugador** y se pregunta un segundo después del
  join: el cliente se anuncia *después* de entrar, y preguntar antes deja
  "vanilla" cacheado toda la sesión.
- **La librería recuerda lo que mandó y lo repone** al reconectar y al cambiar
  de mundo (solo lo del mundo nuevo). En memoria: un waypoint es algo en una
  pantalla, no un registro que merezca disco.
- **Un fallo de la integración no sale de ahí.** Es el bug de otro plugin; el que
  pidió el waypoint no hizo nada malo.

### Clanes — un proveedor activo, caché por jugador, sin ramificar

Todo lo que dependa de un plugin de clanes pasa por `net.exylia.lib.clan`.

- **El plugin nunca pregunta qué plugin de clanes hay.** Pregunta lo que quiere
  saber (`Clans.areAllied(...)`) y la lib responde con los datos que tenga.
- **Un proveedor es una clase que implementa `ClanProvider`.** Cada una
  referencia su plugin por reflexión (SimpleClans, Kingdoms, UltimateClans) o
  adapta un `ClanBridge` externo. Añadir una no toca nada más.
- **La detección prioriza bridges externos sobre built-ins.** Un bridge
  registrado con prioridad 10 gana a cualquier detección automática.
- **La caché es Caffeine con TTL de 3 segundos**, porque estas llamadas van en
  el hot path del daño, el kill message, el scoreboard. Invalidada en
  `Clans.invalidate()` y en `forget(player)`.
- **Lo que un plugin no tiene se devuelve vacío.** UltimateClans no tiene
  alianzas → `alliesOf()` devuelve `[]`, no tira excepción. Preguntar si dos
  clanes son aliados cuando uno no existe devuelve `false`.

### Utils — modular, auto-contenidas, sin dependencias entre ellas

Todo lo que dé utilidad a los plugins pero no tenga sitio en un módulo
específico va a `net.exylia.lib.util`.

- **Cada clase es una utilidad auto-contenida.** No dependen entre sí, y nada
  fuera del módulo sabe cómo funcionan por dentro.
- **Effects** parsea strings de pociones del formato
  `SPEED:1:300|JUMP_BOOST:2:120`. El parseo produce `ParsedEffect` (records
  Java estándar) sin tipos Bukkit, con caché Caffeine de 30 segundos. El
  resolver (`PotionEffectType.getByName`) y el applier (`addPotionEffect`) son
  inyectables para tests.
- **Cooldowns** guarda por jugador un mapa de clave → instante de expiración.
  No hay tarea que descuente: se compara al leer, así que mil cooldowns
  inactivos cuestan cero. Lo expirado se purga en la lectura que lo detecta, y
  al salir el jugador se olvida entero — el mapa no puede crecer sin límite.
  El reloj es inyectable para que los tests no duerman. Los segundos redondean
  **hacia arriba**: decirle "0 segundos" a alguien a quien aún le niegas la
  acción es mentirle.
- **`Cooldowns` es LA base de todos los cooldowns del ecosistema.** Ítems, chat
  y lo que venga se construyen encima, nunca en paralelo. En ExyliaCommons
  había cuatro implementaciones distintas y una (`channel`) tenía
  `getRemainingSeconds()` devolviendo `0` siempre: nadie mira la cuarta copia.
- **Un cooldown lo identifican `CooldownScope` + clave.** El scope es su tipo y
  su id, así que `clan:red` y `team:red` son dueños distintos. Los scopes de
  jugador se cachean, no se reconstruyen: vale 7 ns en la ruta caliente.
- **Persistencia por umbral, sin configurar nada: >= 5 minutos va a disco.** Lo
  decide la duración, no el llamante. Menos de eso no compensa la escritura y
  habría expirado antes de leerse. Se escribe async, solo de los dueños cuyos
  cooldowns largos cambiaron, a fichero temporal + move atómico.
- **Al capa de ítems solo le toca lo suyo**: el overlay de Bukkit y la clave por
  material bajo `item:`. Contar el tiempo es de la base.
- **El tiempo se muestra con decimales.** `remainingSeconds` devuelve
  `double`; `remainingWholeSeconds` sigue redondeando arriba para mensajes de
  "espera N segundos"; `remainingFormatted` da el texto listo pasando por
  `TimeFormats`, la única implementación de formato de la lib (public en
  `util`, compartida con `effect`).
- **Un display lee un cooldown, no lo duplica.** `Timer.ofCooldown(...)` es un
  puente: el cooldown sigue siendo la verdad, el display solo mira. `advance` y
  `extend` no hacen nada en ese timer; dar más tiempo se hace vía `Cooldowns`.
- **Medir antes de apilar.** El benchmark existe y está en el repo: ~32 ns con
  cooldown activo, ~8 ns cuando no hay nada. Cuando añadí scopes subió a 89 ns
  por culpa de `UUID.toString()` en cada llamada — el mismo pecado que le
  critiqué a commons. Se arregló guardando el UUID y cacheando el scope.
- **Las futuras utilidades** (inventarios, timestamps, etc.) siguen el mismo
  patrón: clase propia, caché donde tenga sentido, y una costura inyectable
  (reloj, resolver, overlay) para que se puedan testear sin servidor.

### Comandos — siempre Lamp, nunca un executor a mano

Todo comando se escribe con **Lamp** (`io.github.revxrsal:lamp.*`), la base
del ecosistema: `compileOnly` en Gradle y `libraries:` en el plugin.yml para
que el servidor la descargue de Maven Central. Nunca `onCommand`, ni un
`CommandExecutor` propio, ni otra versión que la del resto de plugins.

### Reload — cada cual recarga lo suyo

- **No hay sistema de reload.** `Configs.reloadAll(plugin)` + `onReload` lo
  cubren; un plugin se recarga a sí mismo en tres líneas y nunca toca la lib.
- **`/exylialib reload` recarga solo la paleta** y basta para recolorear todo
  el servidor: `Colors.apply` → se descarta la caché de `TextEngine` →
  `BoardManager` y `HologramRuntime` se re-envían enteros.
- **"Reload lib → reload plugin" está prohibido**: el plugin no necesita nada
  de la lib para recargarse, y recargar la paleta desde un consumidor
  re-enviaría los visuales de TODOS los plugins.
- **Un plugin declara su reload con `Reloads`**: pasos con nombre, en orden, y
  un paso que revienta **no aborta los siguientes** — se reporta por su nombre
  y se sigue. Un reload a medias sin avisar es peor que uno fallido.
- **La lib avisa, no invoca.** `Reloads.onLibraryReload(plugin, action)` corre
  tras `/exylialib reload`; sirve para lo que un plugin parseó una vez y
  guardó (una GUI de `onEnable`). Un listener que revienta se reporta contra
  su propio plugin y no frena a los demás; se liberan al deshabilitar.
- **`step` normal NO escucha a la lib.** Solo `stepAlsoOnLibraryReload`.
  Re-leer los ficheros propios de un plugin no es lo que significa un
  recoloreo.
- **Reload es síncrono.** Leer YAMLs pequeños y reenviar packets no necesita
  futures ni orquestador: eso era la ceremonia de commons.
- **Un módulo que guarda un `Component` (o algo derivado de la paleta) más
  allá de un render DEBE exponer `invalidateAll()`** y ser llamado desde el
  listener de la paleta en `ExyliaLib.loadPalette`. Es requisito para que un
  módulo nuevo entre en la lib.
  - Ya conectados: `TextEngine` (vía `Colors.apply`), `BoardManager`,
    `HologramRuntime`, `EffectRuntime`.
  - El atajo de "texto estático se dibuja una vez" es justo lo que crea este
    bug: en 1.16.0 los efectos estáticos se quedaban con los colores viejos.
  - Lo que un módulo cachea sin relación con la paleta (clanes, pociones
    parseadas, cooldowns) se deja en paz a propósito.
  - La tabla de qué recarga qué está en `docs/reload.md` y la cubre
    `PaletteReloadTest`.

### Debug — seis métodos y un toggle

Todo mensaje a consola pasa por `net.exylia.lib.debug.Debug`. Nunca
`System.out`, ni ANSI a mano, ni un logger propio por plugin.

- **`log`, `success`, `warn`, `error`, `debug`.** No hay categorías, niveles
  numéricos ni configuración de formato. Commons tenía cuatro ejes de
  clasificación y cuarenta entry points para decir estas cinco cosas; a las 3
  de la mañana nadie elige bien entre cuarenta opciones.
- **El color sale de la paleta del servidor** y el mensaje se anexa literal:
  una traza llena de `&` y `{}` sale tal cual.
- **`debug()` solo imprime con `enabled(true)`**; el toggle lo da la config
  del plugin. El resto siempre imprime.
- **El banner (`motd()`) es el nombre en ASCII art + versión**, lo que se
  echaba de menos de commons. jfiglet va shadeado y relocado, fuera del POM.
- **Nunca rompe un arranque**: sin fuente en un jar roto, imprime el nombre
  en plano.

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
8. **Responde al reload.** Si el módulo guarda algo derivado de la paleta,
   expone `invalidateAll()` y se engancha en `ExyliaLib.loadPalette`; si no
   guarda nada, se documenta que no aplica. Ver *Reload* y `docs/reload.md`.

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

## Documentación y mapa de rutas

La documentación de usuario vive en `docs/`, **un fichero por módulo**, con
índice en `docs/README.md`. Es producto (la librería es abierta): va en
inglés, como el README y el Javadoc. Este AGENTS y la comunicación interna, en
español.

### Reglas para documentar (anti-alucinación)

1. **Se documenta contra el código, nunca de memoria.** Antes de escribir o
   tocar un doc, extrae las firmas reales:

   ```bash
   grep -n "    public" src/main/java/net/exylia/lib/util/Cooldowns.java
   ```

   Si el doc y el código discrepan, el doc está mal y se corrige en ese
   commit.
2. **Toda API nueva o cambiada actualiza su doc en el mismo commit.** Un PR
   que cambia `Cooldowns.java` y no toca `docs/cooldowns.md` está incompleto.
3. **El doc describe contratos** (qué hace, hilos, nulabilidad, ciclo de vida,
   coste medido), no la implementación. Lo que cambia libremente en
   `internal/` no se promete en un doc.
4. **Cada módulo lleva su `@since`** en el índice y en el Javadoc. Mapa de
   versiones abajo.
5. **Los números de rendimiento que se afirman salen de un benchmark en el
   repo.** Si no hay medición, no se afirma el número; se escribe el diseño
   ("se compara al leer, no hay task") sin cifra.
6. **Las rutas de abajo son la fuente para localizar código.** Léelas antes
   de buscar con grep a ciegas; están pensadas para que documentar o cambiar
   un módulo no exija re-explorar el repo.

### Mapa de módulos

Raíz de código: `src/main/java/net/exylia/lib/`. Raíz de tests:
`src/test/java/net/exylia/lib/` (misma estructura de paquetes).

| Módulo | API pública | Interno | Doc | Desde |
| --- | --- | --- | --- | --- |
| task | `task/Tasks`, `TaskScheduler`, `TaskHandle`; `platform/Platform` | `task/internal/` | [docs/task.md](docs/task.md) | 1.0.0 |
| config | `config/Configs`, `ConfigFile`, `MutableConfig`, `Key`, `Comment`, `Migration`, `ConfigIssue` | `config/internal/` | [docs/config.md](docs/config.md) | 1.1.0 |
| text | `text/Text`, `Colors`, `Palette` | `text/internal/` | [docs/text.md](docs/text.md) | 1.2.0 |
| placeholder | `placeholder/Placeholders`, `Template`, `Resolver`, `Request` | `placeholder/internal/` | [docs/placeholders.md](docs/placeholders.md) | 1.3.0 |
| effect | `effect/Effects`, `Timer`, `Ticks`, `Display`, `EffectConfig` | `effect/internal/` | [docs/effects.md](docs/effects.md) | 1.4.0 |
| scoreboard | `scoreboard/Scoreboards`, `Board`, `SidebarConfig` | `scoreboard/internal/` | [docs/scoreboard.md](docs/scoreboard.md) | 1.5.0 |
| hologram | `hologram/Holograms`, `Hologram`, `HologramConfig` | `hologram/internal/` | [docs/hologram.md](docs/hologram.md) | 1.6.0 |
| client | `client/Clients`, `Waypoint`, `Cooldown`, `ClientBrand` | `client/internal/` | [docs/client.md](docs/client.md) | 1.7.0 |
| clan | `clan/Clans`, `Clan`, `ClanBridge` | `clan/internal/` | [docs/clan.md](docs/clan.md) | 1.8.0 |
| util (pociones) | `util/Effects` | — | [docs/util.md](docs/util.md) | 1.9.0 |
| util (cooldowns) | `util/Cooldowns`, `CooldownScope`, `PluginCooldowns`, `ItemCooldowns` | `util/internal/CooldownStore` | [docs/cooldowns.md](docs/cooldowns.md) | 1.10.0 |
| scopes + persistencia + items | (mismos ficheros) | `ExyliaLib` (join/quit/shutdown/timer) | docs/cooldowns.md | 1.11.0 |
| decimales + `TimeFormats` + `Timer.ofCooldown` | `util/TimeFormats`; `effect/Timer` | `effect/internal/CooldownTimer` | docs/util.md, docs/effects.md | 1.12.0 |
| debug | `debug/Debug` | jfiglet shadeado (`internal/jfiglet`) | [docs/debug.md](docs/debug.md) | 1.13.0 |
| comando `/exylialib` | — | `internal/ReloadCommand`, `internal/Commands` (Lamp confinado) | [docs/reload.md](docs/reload.md) | 1.14.0 |
| reload | `reload/Reloads` (+ `Reloads.Report`) | disparo en `ExyliaLib.loadPalette`; liberación en `onPluginDisable`/`onDisable` | [docs/reload.md](docs/reload.md) | 1.15.0 |
| efectos en mensajes + centrado | `text/Centering` | `text/internal/EffectTag`, `EffectTagPlayer`, `text/FontWidths` | [docs/text.md](docs/text.md) | 1.17.0 |
| prefijo por plugin | `text/Prefixes` | sustitución en `Text.build`; limpieza en `ExyliaLib.onPluginDisable` | [docs/text.md](docs/text.md) | 1.17.2 |

Clases raíz que no son módulo: `ExyliaLib.java` (ciclo de vida y limpieza),
`platform/Platform.java`, `internal/LibrarySettings`, `internal/ExyliaLibUpdater`.

### Costuras inyectables para tests (no las elimines)

Son package-private a propósito; los tests viven del mismo paquete:

| Clase | Costura |
| --- | --- |
| `util/Cooldowns` | `setClock/resetClock` (reloj), `installStore/removeStore` (persistencia), `trackedOwners/dirtyCount` (observación) |
| `util/ItemCooldowns` | `setOverlay/resetOverlay` (el `setCooldown` de Bukkit) |
| `util/Effects` | `setResolver/setApplier`, `resetCache` |
| `debug/Debug` | `setSink/resetSink` (a dónde van las líneas) |
| `reload/Reloads` | `listenerCount()` (observación de fugas) |
| tests compartidos | `src/test/java/net/exylia/lib/FakeServer.java`, `FakePlayer.java`, `debug/DebugCapture.java` |

### Protocolo de release (resumen; el detalle está en *Verificación*)

1. `./gradlew clean build` — verde, cero warnings.
2. Tests + sabotajes: rompe la lógica a propósito, el test debe fallar.
3. `publishToMavenLocal` + consumidor externo compilando contra la versión.
4. Doc del módulo actualizada (ver reglas) y README si la sección existe.
5. `version` en `build.gradle`, commit, tag, push; verificar JitPack
   (POM con 0 dependencias; JAR con las clases nuevas). Los tags son
   inmutables: una versión publicada jamás se mueve.

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

### ExyliaLib no se shadea, pero sí shadea

Son dos cosas distintas y conviene no confundirlas:

- **Nadie shadea ExyliaLib.** Vive una vez en el servidor como plugin y los
  plugins la consumen con `compileOnly`. Eso no cambia.
- **ExyliaLib sí mete dentro sus dependencias no instalables**, relocalizadas.
  scoreboard-library es el primer caso: `net.megavex.scoreboardlibrary` viaja
  como `net.exylia.lib.internal.scoreboardlibrary`.

El criterio para decidir cuál de las dos aplica es si la dependencia es
**infraestructura compartida del servidor** o **detalle de implementación de un
módulo**:

| | Ejemplo | Cómo entra |
| --- | --- | --- |
| Se instala en el servidor y la usan varios plugins | PacketEvents, Caffeine, PlaceholderAPI | `compileOnly`, versión única en todos los plugins |
| Solo la usa ExyliaLib por dentro | scoreboard-library | `shade` + relocate |

Shadeada y relocalizada evita el problema que hoy tiene commons: cada plugin
lleva su propia copia con su propio paquete, así que hay tantas instancias de la
librería como plugins. Aquí hay una.

Se declara en la configuración `shade` (no en `implementation`) para que
cumpla las tres condiciones a la vez: compila, se empaqueta, y **no aparece en
el POM publicado** — nadie debe resolver una copia relocalizada.

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
