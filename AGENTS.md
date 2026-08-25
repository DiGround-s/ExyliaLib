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
- **El small text es un booleano, va en `true` por defecto y se aplica sobre la
  plantilla, no sobre los valores.** Es el look de Exylia, así que se activa
  solo; `small-text: false` lo apaga. Se transforma dentro del parse
  cacheado, así que el coste es cero por jugador y por tick; un nombre de
  jugador y un número se sustituyen después y quedan intactos. Transformar el
  valor haría que `Steve` se lea `sᴛᴇᴠᴇ` y obligaría a transformar por jugador
  en cada render, que es justo lo que la caché existe para evitar.
- **El transform respeta lo que es instrucción**: tags, tokens, códigos legacy
  y `%placeholder%`. Los tres primeros dejan de funcionar si se reescriben; el
  cuarto falla **en silencio**, porque el valor se sustituye buscando el nombre
  literal sobre el Component y un nombre reescrito no vuelve a coincidir: el
  `%coins%` crudo llega al chat.
- **No hay "forzar mayúsculas", y no es un olvido.** En commons `a` y `A`
  apuntaban al mismo glifo del mapa, así que el flag no podía cambiar un solo
  carácter. Estuvo años en el config de cada servidor sin hacer nada; portarlo
  sería copiar un bug con formato de feature.
- **Centrar mide el glifo dibujado, no el escrito.** Una mayúscula ocupa cinco
  píxeles y su small cap cuatro: medir el original empuja cada línea centrada
  a la derecha. Y cambiar el switch tira la caché de parseo, como la paleta.
- **Los valores sustituidos son literales por defecto, formateados a pedido.**
  `with()` inserta texto plano (lo que teclea un jugador no puede inyectar
  formato); `withFormatted()`/`forPlayerFormatted()` parsean el valor (un
  display name de config *es* su formato). Elegir mal en cualquiera de los dos
  sentidos es un bug visible en chat. La distinción es **de quién es el valor**,
  no de qué tipo es: lo escribió el dueño del servidor, o lo tecleó un jugador.
- **Un color suelto no puede ser un valor.** La sustitución ocurre sobre el
  Component ya parseado — eso es lo que permite parsear la plantilla una vez y
  compartirla entre todas las filas. Un color sin texto parsea a un componente
  vacío con un color puesto, y un color en un nodo no alcanza a su hermano: en
  `%name_color%WILD`, `WILD` está al lado, no dentro. Da igual el hex o el
  token, y da igual `withFormatted`. Se pasa la frase coloreada entera, o se
  usa una plantilla por estado. ExyliaArmorTrims mandaba `{accent}` como valor
  de fila y pintaba los ocho caracteres en el ítem.
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

### Actions — compilar una vez, adaptar en el borde
Actions es el núcleo compartido por menús, items y otros triggers, pero no sabe
qué es un click, una mano o un slot.

- Registry siempre por plugin y namespace: `Actions.of(plugin, "practice")`.
- En YAML público siempre `namespace:id`; los IDs simples solo se aceptan al
  compilar desde el `PluginActions` dueño.
- El string se compila al cargar config (`ActionCall`), nunca en cada click.
- Sync corre directo. Async solo para I/O real mediante `registerAsync`, que usa
  Tasks; no agendar un simple `closeInventory()`.
- Datos específicos mediante `ActionKey<T>` definidos por UI/Items, nunca mapas
  con claves string en el core.
- Solo SUCCESS continúa una `ActionSequence`; STOP, DENIED y FAILED la terminan.
- Delays pertenecen a `ActionStep` y se agendan en la entidad del jugador.
- `execute` devuelve `ActionExecution`: quien abre algo con pasos demorados
  (un menú, un item) debe cancelarlo al cerrarse. No dejar tasks vivas.
- Acciones que dependen de la fila que se dibuja: `PluginActions.template`.
  Una plantilla sin placeholders se compila al cargar y no cuesta nada luego.
- No duplicar cooldowns, permisos, auditoría ni rate limits en un pipeline:
  usar el módulo especializado cuando una acción concreta lo necesite.

### Items — una definición, muchos renders

Todo ítem que venga de una config pasa por `net.exylia.lib.item`. Nunca un
`ItemStack` construido a mano desde un `ConfigurationSection`, ni una copia del
parser dentro de un plugin.

- **`Item` es una definición, no un `ItemStack`.** Guarda sus placeholders sin
  resolver, la comparten todos los que la miran, y se testea sin servidor.
  Leer el fichero es caro y pasa una vez; construir el ítem es barato y pasa
  constantemente. Commons hacía las dos cosas juntas en cada render.
- **No es un módulo de menús.** SpecialsV3, PracticeCore (hotbar del lobby),
  Shields y SurvivalCore lo usan sin abrir ninguna GUI; `ui` es un consumidor
  más. Por eso vive fuera de `ui` y no dentro.
- **`Items.of(plugin)`, no estático.** Los valores de `nbt` van bajo el
  namespace del plugin dueño. Commons guardaba un `plugin` estático, que en una
  librería compartida archivaría todo bajo `exylialib:`.
- **El prefijo de cabeza vive en `material`.** `basehead-`, `headbase-`,
  `urlhead-`, `playerhead-`, `bytes:` — 440 usos reales. `Source` los resuelve
  al cargar; `startsWith` en caliente era de commons.
- **`playerhead-%player_name%` es un tipo aparte** (`OfHeadTemplate`): el único
  que obliga a resolver por jugador. Saberlo al cargar es lo que permite que
  los otros cincuenta slots no paguen nada.
- **`name` y `display-name` son cosas distintas**, no un par con fallback. El
  primero se pinta; el segundo lo cita un plugin en un mensaje. Tratarlos igual
  mandaba al chat un nombre en negrita con degradado y un contador dentro.
- **Un ítem estático se renderiza una vez y se copia.** Dinámico es cualquiera
  con placeholder, cabeza por plantilla o trim con placeholder. Los que llevan
  `nbt` nunca se cachean: el namespace del dueño los hace distintos.
- **La caché se tira al recargar la paleta.** Requisito de la barra de calidad;
  enganchado en `ExyliaLib.loadPalette` junto a los demás.
- **Cero reflexión.** Contra paper-api 1.21.4 los data components son API
  directa. Commons tenía 202 líneas de reflexión solo para consumibles y 152
  para atributos porque soportaba servidores anteriores; nosotros no.
- **Una parte ilegible se reporta y se salta**, el ítem sigue. Commons se la
  tragaba, así que un encantamiento mal escrito era indistinguible de uno bien.
- **Cuatro bugs de commons se arreglan a propósito**, y cada uno tiene su test:
  `flags` no se parseaba en ningún sitio; `hide-attributes` era
  `getBoolean(a,true) || getBoolean(b,true)` y no se podía apagar; `upgraded`
  se guardaba y no se leía, así que un refill de Instant Health II daba I; y
  `display-name` era fallback de `name`.
- **`hide-attributes` esconde todo lo que el cliente escribe solo**, no solo las
  líneas de daño y velocidad: también el bloque que se añade a sí mismo un
  smithing template ("Applies to: Armor"), una poción, un firework o un banner.
  Un menú que pide un tooltip limpio se refiere a todo eso. Desde 1.20.5 eso
  vive en data components y la flag es `HIDE_ADDITIONAL_TOOLTIP`; contra
  paper-api 1.21.4 son API directa, así que aquí no hace falta la reflexión que
  commons usaba para lo mismo.
- **La `ItemFlag` sola no alcanza, y el enum lo dice**: una flag puesta «sin
  poner también el dato que esconde puede no persistirse». Un smithing template
  no guarda ese dato — su bloque sale del *tipo* de ítem — así que la flag se
  aplicaba, el test la veía puesta, y el ítem seguía diciendo "Applies to:
  Armor" en pantalla. Hace falta **además** el data component
  `HIDE_ADDITIONAL_TOOLTIP`, que está definido contra el tipo. Commons llegaba
  al mismo componente por reflexión porque soportaba servidores viejos.
- **El componente se escribe al final del render, después del último
  `setItemMeta`.** Cada `setItemMeta` reemplaza el mapa de componentes entero
  del ítem, y `TraitApplier` lo llama seis veces *después* de `write`. Escribirlo
  dentro de `write` lo ponía y lo borraba un instante después: sin aviso —
  porque la escritura sí ocurría — y sin efecto. Se ve idéntico a un cliente que
  ignora el componente, y costó tres despliegues distinguirlo.
- **`-Dexylia.item.components=true` lo dice.** Imprime una vez por qué ruta se
  escribió y qué componentes sobrevivieron en el ítem terminado. Apagado no
  cuesta nada; existe porque "se escribió pero no se ve" y "no se escribió" son
  indistinguibles desde fuera.
- **`DataComponentTypes` vive confinado en `ItemComponents`.** Resuelve cada
  constante contra el registro del servidor en un inicializador estático, así
  que nombrarla ya exige un servidor vivo. Confinada, `ItemRenderer` sigue
  cargando sin uno — que es lo que permite testear la decisión. Verificado en
  bytecode, igual que PacketEvents y Folia.
- **Ese componente se busca por nombre en el registro, nunca como campo.**
  Compilamos contra 1.21.4 y los servidores corren más adelante: en 1.21.11
  `HIDE_ADDITIONAL_TOOLTIP` ya no existe (lo reemplazó `TOOLTIP_DISPLAY`).
  Nombrar el campo compila igual y revienta en runtime con `NoSuchFieldError`
  **dentro del render**, así que el menú entero no abre. Pasó en producción con
  1.46.0. `Registry.DATA_COMPONENT_TYPE.get(...)` existe en las dos versiones y
  contesta con `null` cuando el nombre ya no está.
- **Excepción declarada a "cero reflexión": `TooltipDisplay`.** Es la única de
  la librería y vive entera en `ItemComponents`. La regla se escribió contra el
  caso de commons — reflejar para soportar servidores anteriores a los data
  components —; este es otro: el componente **cambió de nombre entre dos
  versiones que soportamos a la vez**, y ninguno de los dos nombres compila en
  ambas. `hide_additional_tooltip` se resuelve por registro; `TooltipDisplay`
  no existe en paper-api 1.21.4, así que ni siquiera se puede nombrar el tipo.
  Subir la base mínima sería la alternativa, y hoy es 1.21.4 a propósito.
- **Los métodos se buscan en la interfaz, nunca en `builder.getClass()`.** El
  builder que devuelve Paper es su implementación interna y puede no ser
  pública: un método encontrado ahí no se puede invocar sin `setAccessible`,
  que esto no usa. `TooltipDisplay$Builder` y `DataComponentBuilder` sí son API
  pública.
- **`tooltip_display` esconde componentes, no una categoría**, así que la
  categoría se enumera (`WRITTEN_BY_TYPE`) y se filtra contra el registro: el
  nombre que ese servidor no tiene se salta. Así una lista sirve para varias
  versiones. El bloque del smithing template sale de
  `provides_trim_material`.
- **Lo que no se puede escribir se dice una vez por servidor, no por ítem.**
  Abrir un menú renderiza cada slot: reportar por ítem metió dieciocho líneas
  idénticas en consola por una sola pantalla. Es un hecho de la versión, no un
  incidente del ítem.
- **Un tooltip no vale una pantalla rota.** Lo que no se puede escribir se
  reporta por `Problems` y el ítem se dibuja igual. La regla vale para todo
  data component cuyo nombre no sea estable entre las versiones soportadas; los
  que sí lo son (`FOOD`, `CONSUMABLE`, `ATTRIBUTE_MODIFIERS`) se siguen
  nombrando directo en `Components`.
- **Compilar contra 1.21.4 no es correr en 1.21.4.** La base mínima es 1.21.4 a
  propósito, así que toda API nueva se verifica también contra la versión que
  corre el servidor antes de usarla.
- **Un test que pregunta por la flag no prueba esto.** La flag estuvo puesta
  todo el tiempo mientras el jugador veía lo contrario; se verifica que se pide
  el componente, no que se añade la flag.
- **Pero no esconde encantamientos.** Commons aplicaba `ItemFlag.values()`, así
  que un ítem que pedía ocultar atributos perdía las líneas de encantamiento que
  quería enseñar. Ocultar esas sigue siendo algo que el fichero pide, vía
  `flags`.
- **El sonido del consumible se le pide a `Effects`.** La regla no es mecánica
  (`BLOCK_NOTE_BLOCK_PLING` conserva su guión bajo) y ya está escrita una vez.
- **Los registros van por `Registry`, nunca `valueOf`.** Varios de esos tipos
  dejaron de ser enums en 1.21: `values()` compila y revienta en runtime con
  `IncompatibleClassChangeError`.

### Menús — el estado en la sesión, nunca en un mapa por jugador

Todo menú pasa por `net.exylia.lib.ui`. Nunca un `Inventory` abierto a mano, ni
un `InventoryClickEvent` propio, ni un mapa estático de jugador a lo que tiene
abierto.

- **Tres cosas separadas a propósito.** `UiDefinition` es lo que dice el fichero,
  compilado una vez y compartido; `UiSession` es la ventana de un jugador y lo
  único contra lo que se valida un click; `UiEntry` es una fila con sus valores
  y **la cosa de la que trata**.
- **`UiItem` compone un `Item`.** El aspecto es del módulo `item`, que usan
  cuatro plugins sin abrir ninguna GUI. Aquí solo vive lo que únicamente
  significa algo en una pantalla: clicks, condición, dependencias, animación.
- **Las secciones son de primera clase.** Un menú puede tener varias listas
  paginadas a la vez (13 ficheros reales lo hacen). Un bloque `pagination` se
  lee como una sección llamada `main`, así los 153 ficheros de una sola lista no
  se enteran.
- **Las plantillas se leen por forma, no por lista.** Cualquier clave acabada en
  `template` es una, nombrada por lo que va antes. Hay 167 nombres distintos en
  el ecosistema y un plugin puede inventar otro mañana.
- **La fila lleva su valor.** `UiKeys.ENTRY`. Commons no tenía dónde ponerlo, así
  que un handler reconstruía el kit desde el ítem dibujado — de ahí los mapas
  estáticos por jugador, y de ahí que dos menús abiertos se contestaran mal.
- **Una fila dice en qué estado está, no de qué color es.** `.template(...)`
  elige `selected` / `no_permissions`, y el fichero decide cómo se ve cada uno.
  Mandar el color como valor esconde la paleta en Java y no funciona (ver *Texto
  y color*); además deja al dueño del servidor con un `%name_color%` que no
  puede tocar.
- **`withFormatted` en una fila es para valores que traen color *y* texto.** Un
  display name de config. Lo que teclea un jugador va por `with`. Elegir mal se
  ve en pantalla y se reporta; al revés sería una inyección silenciosa, así que
  el default es el que no parsea. Tres plugins lo eligieron mal en la misma
  línea (`effect_description`), lo que dice más de la ergonomía que de ellos.
- **Un valor con `<nl>` se convierte en varias líneas de lore.** Es la única
  forma de que una descripción de config ocupe dos líneas: `<nl>` del fichero lo
  parte `ItemReader` al cargar, y eso no alcanza a un valor que solo existe al
  dibujar. Commons lo permitía porque sustituía sobre el string; devolver una
  sola línea tiraba el resto **en silencio**.
- **Expandir no cuesta un parse por línea.** Todas las líneas salen del mismo
  string de plantilla, así que comparten entrada de caché: el coste es una
  sustitución por línea. Por eso la premisa de "la plantilla se parsea una vez"
  sigue en pie.
- **Cada línea expandida conserva lo que la plantilla pone alrededor** (el
  bullet, el color), los valores vecinos se repiten en todas, y solo se estira
  la línea que menciona el valor largo. Estirar las demás convertiría un tooltip
  de cinco líneas en quince.
- **Expandir no es una segunda puerta al parser**: una línea expandida sigue
  siendo literal salvo que se haya pedido `withFormatted`.
- **Un click se valida contra lo dibujado, no contra el packet.** El cliente
  manda un número de slot; el servidor ya sabe qué puso ahí. Un slot cuya
  condición falla no está en blanco: **no está**.
- **La sesión se encuentra por el holder de la ventana**, nunca por un mapa
  jugador → menú. Un jugador que abre un cofre encima de un menú no es nuestro.
- **Una condición ilegible oculta el slot.** Fallar al revés le da un botón a
  quien no debía tenerlo; lo primero solo lo hace invisible.
- **Nada sobrevive a su pantalla.** Un `ActionExecution` con pasos demorados se
  cancela al cerrar; deshabilitar un plugin cierra sus ventanas **antes** de
  soltar sus tasks, porque un botón cuyo classloader se está muriendo no debe
  contestar otro click.
- **`next_page`, `previous_page`, `back`, `close`, `refresh` son de la lib.**
  Pasar de página no es una feature de nadie y 500 ficheros ya las escriben. Si
  un plugin registra la suya con ese nombre, gana la suya.
- **Los números de página los pone el menú, no el plugin.** La sección sabe
  cuántas filas tiene y cuál está mirando; pedírselos al llamante es pedirle
  que calcule lo que la lista ya calculó. Por eso un valor de contexto con ese
  nombre **no** los pisa: sobreviviría al clic que los movió.
- **El título sigue al lector, y eso es un packet.** En Bukkit el título es un
  argumento de `createInventory` que se lee una vez; el cliente en cambio acepta
  un segundo "open window" para el contenedor que ya tiene abierto y lo trata
  como un retitulado — los slots no se mueven y no parpadea. Sin PacketEvents se
  queda en la página en la que abrió, que es lo que hacía antes, y todo lo demás
  funciona igual.
- **Solo se reenvía si el título nombra una página y además cambió.** Retitular
  obliga al cliente a volver a pedir el contenido de la ventana: demasiado para
  un título que dice lo mismo.
- **Cambiar de página redibuja esa lista y nada más.** `invalidate(dep)` redibuja
  solo los slots que declararon depender de eso. Sin rebuild completo, sin
  parpadeo, sin packets para lo que no cambió.
- **Un clic redibuja todo lo que puede cambiar, no solo el slot clicado.** Un
  botón rara vez se cambia solo a sí mismo: añadir una capa mueve un contador,
  un preview y una lista, y ninguno es el slot que recibió el clic. Redibujar
  uno solo dejaba el resto mostrando lo de antes, que es exactamente "hay que
  clicar dos veces". Y se lee el contexto vivo, así que un redibujado que hizo
  el plugin entre medias no se deshace.
- **`refresh: SMART` redibuja solo lo que puede cambiar.** Un timer que repinta
  decoración estática son packets para un ítem idéntico. El timer solo arranca
  si hay algo que pueda cambiar, y muere con el jugador.
- **La animación dibuja primero y esconde después.** Todo queda registrado antes
  de empezar, así un click sobre un slot que aún no se ve funciona igual. Al
  revés sería una ventana en la que los botones no hacen nada en silencio. Un
  click salta el resto.
- **Los tres fillers son tres cosas.** `global` es fondo; `pagination` es lo que
  ve quien tiene una lista vacía y **suele decir por qué**; `custom` son paneles
  con sus propios slots. Tratar el segundo como fondo deja al jugador sin
  explicación.
- **Una flecha en `navigation` pagina sola.** El `actions` es implícito: una
  flecha declarada ahí no tiene otro trabajo, y Commons paginaba **por slot**
  (`MultiPaginationMenu.handleClickInternal`), así que ningún fichero del
  ecosistema lo escribía. Exigirlo dejaba 12 bloques reales con flechas que se
  dibujaban, se podían clicar y no hacían nada. La que nombra otra cosa
  conserva la suya, y si las built-in no están registradas la flecha se dibuja
  igual: es una comodidad, no un motivo para no cargar el menú.
- **Un botón de página sin página no se dibuja**, y su slot vuelve al fondo que
  le tocaba (el panel que lo reclama, o el `global`, o nada). Commons pintaba
  los fillers *antes* que la navegación, así que el botón que no dibujaba ya
  estaba tapado; aquí la navegación va última y hay que decirle a qué volver.
  Una flecha que existe y no hace nada es la mitad de los menús de un servidor
  tranquilo.
- **Los sonidos se leen de `open_sounds:` en la raíz**, que es como están escritos
  los 2028 ficheros. Leer solo un bloque `sounds:` dejaba mudo al ecosistema
  entero y pasaba los tests igual.

### Wizards — un flujo, un dueño, nada a medias

Todo flujo guiado pasa por `net.exylia.lib.util.wizard`. Nunca una cadena de
inputs encadenados a mano, ni un `static Map<UUID, ...>` con el estado a medio
construir.

- **`EventConfigWizard` de ExyliaEvents es lo que esto arregla**, y es código
  real: 94 líneas para preguntar dos cosas. `askConfigId` llama a
  `askDisplayName` llama a `finishCreation`, y entre medias el tipo de evento
  vive en un `static Map<UUID, String>`. De ahí salen las tres consecuencias, y
  las tres están en el fichero: la rama de cancelar copiada cuatro veces (una
  por cada salida que alguien recordó — y la que nadie recordó, un timeout o un
  quit, no borra nada, así que `hasPendingWizard` sigue diciendo `true` de un
  flujo que ya no existe); ninguna vuelta atrás, así que quien se equivoca en el
  id se entera en el paso dos y empieza de cero; y el menú que lo abrió se
  reabre solo en la ruta de éxito, así que quien cancela se queda mirando la
  nada. No es que estuviera mal escrito: es que no había un objeto que **fuera**
  el flujo, solo callbacks que cada uno conocía al siguiente.
- **Uno por jugador en todo el servidor.** Un segundo flujo termina el primero
  como `REPLACED`. Es la misma regla que el módulo `input` ya impone con su
  única pregunta activa, y por la misma razón: un wizard **es** una cadena de
  inputs, así que dos flujos vivos estarían esperando el mismo hueco — la
  siguiente pregunta contestaría al paso del otro, y el otro al suyo. Se
  intercambian las respuestas y ninguno entrega nada usable.
- **Nada se aplica hasta confirmar.** Con `summary()`, `onFinish` corre
  **exactamente una vez** y solo en `COMPLETED`. Un run cancelado, caducado,
  desconectado, reemplazado o fallido no llega ahí nunca. Eso es lo que permite
  crear la cosa entera de una vez en lugar de ir acumulando medio-objetos paso a
  paso, que es lo que hace commons.
- **Un solo camino de limpieza**, no una rama por paso. Toda salida reclama el
  hueco terminal de forma atómica y suelta las cuatro cosas que el jugador nota:
  la pregunta abierta, el selector de bloques, la barra de progreso y el hueco de
  wizard del jugador. Cada una va protegida por su cuenta: una barra que no se
  deja parar no puede impedir que se suelte el selector.
- **El selector de bloques es único por jugador en TODO el servidor**, y es la
  razón afilada de que el camino sea uno solo. Las otras tres cosas son nuestras:
  una barra colgada es nuestra barra, una pregunta colgada es nuestra pregunta, y
  un hueco colgado solo bloquea los wizards de esta lib. Un selector colgado deja
  a ese jugador sin poder seleccionar un bloque para **ningún** plugin — un claim
  de WorldGuard, el setup de otra arena, una región de tienda — hasta que
  reconecte. Es la única fuga del módulo que el jugador se lleva puesta fuera del
  plugin que la causó. Un paso `region` que no puede reclamarlo termina como
  `REPLACED` nombrando al dueño actual, no peleándose por los clicks.
- **El back vive en el resumen, y es un `confirm` más un `choice` y nada más.**
  Negar el resumen ofrece la lista de respuestas; elegir una la vuelve a
  preguntar y devuelve al resumen. Se construye con esas dos peticiones a
  propósito: no pide ningún control que a algún transporte le falte, así que
  funciona idéntico en diálogo nativo, formulario Bedrock, yunque, menú y chat.
  Una pantalla de revisión propia habría que escribirla cinco veces, y cuatro se
  pudrirían. Las rondas están acotadas por `maxRedos`; pasarse cancela, porque
  quien va por la cuarta vuelta ya no está contestando.
- **Cambiar una respuesta de la que depende una rama re-resuelve el flujo.** Se
  re-camina la definición entera desde arriba contra las respuestas actuales: lo
  que pertenecía a pasos que ya no aplican se tira, y lo que ahora aplica se
  pregunta **antes** de que vuelva el resumen. Lo demás sobrevive por nombre, así
  que el jugador no vuelve a teclear lo que ya tecleó. Volver directo al resumen
  — que es lo que hacía la primera versión — está mal en las dos direcciones y en
  silencio: quien cambia KOTH por CONQUEST le manda a `onFinish` un `points` que
  ese tipo de evento no tiene, y al revés le manda respuestas sin una obligatoria.
  Ninguno de los dos se ve hasta que el código de creación del plugin lee un
  campo, o sea: en producción, y lo reporta quien creó el evento roto. Rehacer
  una clave que no guarda ninguna rama sigue yendo directo al resumen, que es el
  caso común.
- **No cachea nada derivado de la paleta**, así que no tiene `invalidateAll()` y
  está deliberadamente fuera de `ExyliaLib.loadPalette`. Cada prompt, línea del
  resumen y barra se construye con `Text` en el momento de mostrarse, y el título
  del `Wizard` se guarda crudo, no parseado. Está declarado a propósito: el punto
  8 de la barra de calidad exige decirlo, no callarlo.

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
- **Lo que manda un plugin lleva su nombre.** `Clients.of(plugin)` archiva por
  dueño y nombre, nunca por nombre solo. Dos plugins tienen todo el derecho a
  llamar `spawn` a un waypoint — un lobby y una partida lo hacen — y con la
  clave plana el segundo `show` le borraba el marcador al primero de la pantalla
  del jugador. Es la misma clase de bug que `Effects.stopFor`: `clear` de la
  vista con dueño baja lo suyo, el estático baja el de todos.
- **Lo que un plugin dibujó se baja al deshabilitarlo.** Antes solo se liberaban
  los equipos, así que un waypoint cuyo dueño ya no está no lo podía quitar
  nadie: se quedaba en el minimapa hasta que el jugador reconectara. Al reponer
  tras un reconexión se vuelve a archivar bajo el mismo dueño, o el marcador se
  mudaría al saco sin dueño y su plugin dejaría de poder tocarlo.
- **Un fallo de la integración no sale de ahí.** Es el bug de otro plugin; el que
  pidió el waypoint no hizo nada malo.
- **Un equipo es un registro, no un empujón.** `markers()` dibuja una lista y se
  olvida; una partida que dura tiene que contestar "quién está en este equipo"
  en cada join, muerte, quit y reconexión. Los tres bugs que salían de guardar
  esa lista en un mapa propio son los mismos siempre: un jugador en dos equipos,
  un equipo que sobrevive a la partida, y un miembro que ya se fue. `ClientTeam`
  contesta las tres una vez.
- **Un jugador está en un equipo a la vez, en todo el servidor**, y `of(player)`
  cruza plugins: de quién es el equipo no cambia en cuál está el jugador.
- **Los miembros se guardan por id, nunca como `Player`.** Un equipo que dura
  más que una sesión no puede ser el motivo de que el servidor mantenga viva una
  entidad. El que se desconectó se cae al leer, así que un equipo que nadie
  limpió igual se encoge.
- **Un equipo muere con su plugin.** Igual que todo lo demás de la librería.

### Nametags — packets a todos, no solo a clientes modificados

Todo lo que cambie cómo ve un jugador a otro (color del nombre, glow, colisión,
ver invisibles) pasa por `net.exylia.lib.nametag`. Nunca un `Scoreboard` de
Bukkit, ni teams a mano, ni `setGlowing`.

- **Está fuera de `client` a propósito, y no es un detalle de empaquetado.**
  `Clients` existe para hablarle a Lunar y Feather y no hacer nada para el
  resto; esto son teams vanilla y flags de entidad por packet, así que un
  jugador sin mods ve exactamente lo mismo. Meterlo ahí haría que `ClientLink`
  y `ClientBrand` no signifiquen nada en la mitad del módulo. En commons vivían
  bajo el mismo paquete sin compartir una sola línea de código.
- **Es por espectador, no por jugador.** El mismo jugador es rojo para su
  enemigo y verde para su clan al mismo tiempo, y nada de eso existe en el
  servidor: sin scoreboard, sin team real, sin estado que mantener sincronizado.
- **El llamante declara un estilo, no un nombre de equipo.** El nombre se deriva
  del estilo, así que dos que pintan igual comparten team sin saberlo. Cada
  plugin se inventaba el suyo (`"clan_" + id`) y después tenía que mantenerlo a
  juego con los colores que significaba.
- **El glow no entra en el nombre del team.** Viaja en las flags de la entidad,
  así que dos estilos que solo se diferencian en eso comparten uno.
- **Un color que no cambió no se manda.** Y un team se crea una vez y después se
  le añade; borrarlo y recrearlo cuesta dos packets cada vez, y un team vacío en
  un cliente no cuesta nada.
- **El glow se reescribe al vuelo, no se manda una vez.** El servidor reenvía
  las flags de una entidad cada vez que algo le pasa, y cada una de esas apagaría
  el contorno. Por eso el módulo necesita PacketEvents.
- **Un plugin solo deshace lo que él pintó.** Una partida no puede tapar en
  silencio el color de un clan. Y al deshabilitarse se deshace todo lo suyo:
  una partida que termina mal no deja a nadie rojo para siempre.
- **Sin PacketEvents no falla, no dibuja.** `isSupported()` en `false` y todos
  en blanco.

### Combate — una respuesta, y falla abierto

Todo lo que pregunte si alguien está en combate pasa por
`net.exylia.lib.util.combat`. Nunca un hook propio por plugin.

- **Cuatro plugins tenían su propio hook para la misma pregunta**, y cada uno
  conocía un set distinto de plugins de combate: el mismo servidor contestaba
  cosas distintas según quién preguntara. Uno devolvía `true` en `canAttack` con
  un `TODO` encima.
- **Falla abierto, siempre.** Sin plugin instalado, o si el plugin revienta:
  nadie está tagueado y todos pueden pelear. Al revés, un fallo de integración
  frenaría todas las peleas del servidor.
- **Se cachea el tag y nada más.** Es la pregunta del hot path (daño,
  movimiento, scoreboard) y no cambia en medio segundo. El tiempo restante
  **no** se cachea: es una cuenta atrás, y cacheada se queda quieta y después
  salta. Una escritura tampoco: taguear leyendo un valor viejo taguea por una
  pelea que ya terminó, así que `tag`/`untag` invalidan el suyo.
- **Vacío no es cero.** Un plugin que no cuenta nada devuelve vacío, no un
  record de ceros. "Sin kills" y "nadie está contando" son respuestas distintas,
  y un leaderboard que no las distingue muestra a todo un servidor en cero.
- **`ratio()` lo calcula la lib.** Los plugins no se ponen de acuerdo en qué
  hacer con cero muertes, y un leaderboard que mezcla dos respuestas es peor que
  uno que elige.
- **Reflexión, como en clanes**, y por lo mismo: la lib carga en servidores que
  no tienen ninguno de los dos.
- **Un `CombatBridge` solo escribe lo que su plugin sabe contestar**; el resto
  son defaults, y cada default es lo que hace un servidor sin nada instalado.

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

### Rewards — el formato es de commons, los bugs no

Todo lo que un jugador gana pasa por `net.exylia.lib.util.reward`. Nunca un
`addItem` suelto, ni un `dispatchCommand` a mano, ni una lista de comandos en un
`List<String>` propio.

- **El formato almacenado no se elige.** Hay filas escritas por commons en
  producción (`capture_pending_rewards`, `event_pending_rewards`, los power-ups
  de SurvivalCore). `RewardCodec` lee y escribe exactamente esa forma: los
  nombres de campo son los del bean Lombok viejo, los nulos se omiten, y una
  lista vacía se guarda como `NULL` y no como `[]`. Migrar un plugin es cambiar
  imports.
- **Un campo nuevo solo se escribe si no es el default.** Así una reward que el
  módulo viejo podría haber escrito serializa byte a byte a lo que él escribía, y
  no engorda contra el `VARCHAR(8192)` que esas tablas ya tienen.
- **Un tipo desconocido cuesta una reward, no la lista.** Es lo que permite que
  un plugin sin migrar lea una fila escrita por uno migrado.
- **Nada se destruye.** Commons descartaba el mapa que devuelve `addItem`, así
  que un ítem que no cabía se borraba sin mensaje, sin log y sin fallo. Aquí la
  política es `DROP`, `QUEUE` o `FAIL`; ninguna reproduce aquello. Y `QUEUE` sin
  store, o con un store que revienta, **dropea**: pedir encolar es pedir no
  perderlo, y una base de datos caída no cambia lo que se pidió.
- **Lo que se encola es el sobrante, y no se vuelve a tirar el dado** — ya se
  tiró. Pero conserva permiso y condición: algo que se debía a quien desde
  entonces perdió el rango que lo exigía ya no se debe.
- **Permiso y condición van antes del dado.** Quién *puede* recibir algo no
  depende del azar. Commons tiraba primero, así que una reward rara reportaba
  "no salió" cuando la verdad era un permiso mal escrito.
- **Un skip no es un fallo.** Perder el dado, no tener permiso y reventar son
  tres resultados distintos y se reportan como tres. Contar los tres como fallo
  hacía que el success rate de commons describiera el dado, no la config.
- **Una condición ilegible entrega la reward.** Al revés que en menús, y a
  propósito: la config dice a quién *excluir*, y una condición que nadie puede
  leer no excluye a nadie. Ocultar un botón es invisible; regalar algo que no
  tocaba es ruidoso, y lo ruidoso es lo que hace que se arregle el typo.
- **`chance` y `weight` son preguntas distintas.** La primera es "¿pasa esto?",
  la segunda "¿cuál de estas pasa?". `pick` solo mira el peso; `roll` elige por
  peso y **después** entrega, así que el ganador todavía se enfrenta a su propio
  `chance`.
- **El dinero viaja como texto.** Un decimal que pasa por un `double` camino a la
  base de datos no vuelve igual.
- **La tabla de pendientes es del plugin, no de la lib.** Capture y Events ya
  tienen la suya llena de filas que alguien espera; una tabla impuesta por la lib
  o las ignora o fuerza una migración. Se pasa un `PendingRewards`.
- **Meter el ítem en el inventario está detrás de `ItemGiver`.** Es la única
  parte que necesita un `ItemStack` de verdad, y sacarla permite testear sin
  servidor el resto de lo que decide una entrega: el orden, el dado, el rango, el
  overflow y la cola. Lo demás sí llama a Bukkit (`dispatchCommand`, `giveExp`,
  `hasPermission`), pero contra un jugador falso, no contra un registro.
- **El menú de edición está preparado, no escrito.** `RewardEntry` es inmutable
  con `toBuilder()` que conserva el id, `copy()` duplica, `displayName()` y
  `resolvedIcon()` dibujan sin servidor, y `RewardCodec` va y viene. Un editor
  construido sobre eso no toca nada interno.

### Loot — el formato es de commons, los bugs tampoco

Todo lo que sale de un cofre, un spawner o un bloque roto pasa por
`net.exylia.lib.util.loot`. Nunca una lista de ItemStacks a mano ni un
`nextDouble` suelto contra un peso.

- **El formato almacenado no se elige.** Hay filas escritas por commons en
  producción (`sc_loot_chest_templates`, las tablas de spawners, cada
  configuración de evento). `LootCodec` lee y escribe exactamente esa forma: los
  nombres de campo son los del bean Lombok viejo en su orden de declaración, los
  nulos se omiten, y una lista vacía se guarda como `NULL` y no como `[]`.
- **Una fila sin `type` es un ítem.** Se escribió antes de que existieran las
  entradas de comando, y eso significaba. Un tipo desconocido también se lee como
  ítem y se reporta: cuesta el payload, no la tabla.
- **Una entrada a medio configurar se conserva y se reporta.** Un ítem sin ítem
  es justo lo que un editor está para arreglar; tirarla la perdería en cuanto se
  guardase la tabla.
- **El peso significa dos cosas y las dos son de commons.** `roll` lo lee como
  porcentaje y tira línea por línea (cofre, spawner); `pick` lo lee como parte de
  un total y saca una sola (relleno de survival games). No se convierte entre
  ellas: las tablas de ahí fuera ya significan una u otra según quién las lea.
- **La línea forzada cuando no salió nada se elige uniforme, no por peso.** Es lo
  que hacía commons, y cambiarlo volvería comunes los ítems raros justo en las
  tablas donde toda línea es improbable.
- **Nunca un stack de cero.** Commons devolvía `minAmount` tal cual, así que una
  entrada guardada con `0` producía un ítem de cantidad cero que desaparecía de
  camino al cofre. `amountOf` nunca baja de uno, y un rango al revés da el
  extremo bajo en vez de nada.
- **El módulo no guarda nada.** Sin registro, sin caché, sin dueño por plugin:
  una tabla es una `List<LootEntry>` de quien la tiene (plantilla de cofre,
  spawner, configuración de evento). Un registro en la librería sería un segundo
  sitio que mantener sincronizado.
- **Construir el `ItemStack` está detrás de `LootItems`.** Es la única parte que
  necesita servidor; la gramática escrita, el dado, las cantidades y el códec se
  prueban sin uno.
- **`LootEntry` es inmutable** con `toBuilder()` que conserva el id y `copy()`
  que duplica, así que un admin guardando la tabla no cambia una línea por debajo
  de un cofre que se está llenando.

### Selección de regiones — la herramienta se entrega, el resultado se confirma

El selector de bloques de `region` es producto, no una API cruda. Lo que ve un
admin es lo que veía en ExyliaCommons, sin sus bugs.

- **Hacha de oro, no de madera.** El hacha de madera es la varita de WorldEdit y
  se confunde con ella. El default es `GOLDEN_AXE`, con nombre, lore y brillo.
- **Se entrega de verdad.** Decirle a alguien que seleccione con una herramienta
  que no tiene es no decirle nada. Va a un hueco **libre**: a la mano solo si la
  mano está vacía. Commons hacía `setItemInMainHand` y destruía lo que hubiera —
  eso no se reproduce.
- **Se devuelve pase lo que pase.** Confirmar, cancelar, salir del servidor y
  deshabilitar el plugin pasan por la misma ruta de liberación.
- **Se ve mientras se elige.** Una esquina se dibuja como el bloque que es; dos,
  como la caja que forman. El sampler del outline es el mismo que usa
  `visualize`, así que el coste ya estaba medido y acotado.
- **Dos esquinas son una propuesta, no una respuesta.** Shift + clic izquierdo
  confirma. La primera versión de este módulo respondía en el segundo clic, así
  que un admin que fallaba el bloque ya había creado la arena.
- **Sin confirmación, solo el clic derecho cierra.** Un clic izquierdo nunca
  completa, ni siquiera si ya había una segunda esquina: corregir la que ya
  pusiste no puede terminar la selección.
- **Devolver la herramienta jamás puede dejar colgada la sesión.** Se saca del
  registro y se completa el futuro **antes** de tocar el inventario, y esa parte
  va protegida. Al revés, un `getInventory()` que reventaba dejaba al jugador sin
  poder seleccionar con ningún plugin hasta reconectar. Se captura
  `LinkageError` además de `RuntimeException`: construir un `ItemStack` resuelve
  el registro de ítems y eso llega como `Error`.
- **Nada de `isAir()` en rutas testeables.** Se compara contra `AIR`/`CAVE_AIR`/
  `VOID_AIR`, como ya documenta `item/Source`: `isAir()` pregunta al registro de
  bloques y solo un servidor vivo lo tiene.
- **El scheduler es el de ExyliaLib, no el del consumidor.** Un plugin ya está
  deshabilitado cuando se liberan sus selecciones, y un plugin deshabilitado no
  puede programar la devolución de su propia herramienta. En Spigot y Paper, si
  el llamante ya está en el hilo principal, se escribe en línea: un tick de
  retraso es tiempo suficiente para hacer clic y preguntarse por qué no pasa nada.

### Editores — un motor, y con pilas dentro

Todo lo que un admin edita en pantalla (recompensas, loot, comandos, efectos,
ítems, sitios) pasa por `net.exylia.lib.util.editor`. Nunca un menú propio por
tipo, nunca un mapa de sesiones por jugador.

- **Commons tenía cinco copias de la misma pantalla** — rewards, loot, pociones,
  comandos, mensajes — y cuatro resolvían la fila por número de slot, así que una
  edición que caía después de que la lista cambiara editaba otra fila. Aquí hay
  una pantalla y las filas llevan su elemento.
- **El motor no sabe qué edita.** El dominio conoce al editor
  (`PluginRewards.editor`), nunca al revés. `EditorIsGenericTest` lee el bytecode
  y falla si `util.editor` nombra `util.reward`, `util.loot` o `util.command`.
- **Un motor genérico sin editores no lo usa nadie.** Eso fue exactamente lo que
  pasó con `panel`: se borró sin que ningún plugin lo hubiera tocado. La
  librería trae los descriptores de los tipos que ya son suyos.
- **Nada se escribe hasta guardar.** La lista se copia; cancelar es gratis. Cinco
  finales (guardar, cancelar, cerrar, salir, deshabilitar) y el primero gana;
  cuatro son cancelar, porque una pantalla que te quitan nunca se confirmó.
- **El portapapeles es del jugador, no de la pantalla.** Un cubo por `typeKey`,
  con los elementos que se copiaran — uno o cuarenta —, y pegar no lo vacía:
  pegar la misma tabla en doce cofres son doce clics.
- **Editar una fila es un diálogo, no siete clics.** Todos los campos a la vez y
  **siempre prerellenados**: corregir un nombre no es reescribirlo de memoria.
  Los campos largos piden altura (`lines`), porque una caja de una línea enseña
  veinte caracteres y un display name son doce tokens de color.
- **El icono se inserta, no se sostiene.** Commons leía la mano, lo que obligaba
  a cerrar la pantalla, buscar el ítem y volver — y desde un menú no se podía. Es
  una ventana con un hueco, y el ítem **vuelve siempre**: al confirmar, al
  cerrar, al salir y al deshabilitar el plugin.
- **El estado vive en la ventana.** Nunca un `Map<UUID, Session>`: un jugador con
  un cofre abierto encima de un editor está mirando el cofre.
- **Los pickers leen el registro, no `values()`.** Varios de esos tipos dejaron
  de ser enums y un data pack puede añadir a cualquiera.
- **Un plugin mete sus botones, pero no elige el slot.** La pantalla decide: si
  hay botones, la fila de abajo de filas pasa a ser la banda y la página baja de
  45 a 36; si no hay, se quedan las 45. En commons el slot lo escribía el
  llamante, que es como un botón acaba encima del de guardar cuando alguien
  cambia la pantalla. Caben nueve; el décimo se rechaza al construir, no se deja
  de dibujar en silencio.
- **Un botón toca la copia de trabajo, nada más.** Cargar un preset de 40 líneas
  se deshace con cancelar igual que cualquier otra edición, que es lo único que
  hace seguro ofrecer un botón destructivo.

### Efectos condicionales — la carga es una secuencia, no cuarenta campos

Lo que en ExyliaCommons era `EffectEntry` aquí son dos cosas ya escritas.

- **El `EffectEntry` de commons eran 40 campos y 8 tipos**, con un `switch` que
  crecía con cada tipo nuevo. Su propio javadoc decía que copiaba a
  `RewardEntry`. Aquí la carga es una secuencia y el gating son diez campos.
- **La secuencia ya expresa esos 8 tipos y 5 más**: partículas, sonidos,
  pociones, fuegos, títulos, action bar, chat, rayos, explosiones, romper
  bloques, comandos, formas y pausas. Y compila una vez en vez de reparsear en
  cada play sobre el hilo de región, que es lo que hacía commons.
- **El público es un número, no un enum y un número.** `0` o menos es el jugador
  solo, un número finito son los bloques de radio, `WHOLE_WORLD` es el mundo
  entero. Un enum cuyo significado es "mira el otro campo" son dos formas de
  decir lo mismo y una de decir algo contradictorio.
- **Permiso y condición van antes del dado**, igual que en rewards y por el mismo
  motivo: quién *puede* ver algo no depende de la suerte.
- **`delayTicks` no es un `[DELAY]`.** El de la línea retrasa lo que viene
  detrás; el del entry retrasa ese efecto y deja que los de al lado salgan a
  tiempo.
- **Se lee lo que commons dejó escrito.** `EffectCodec.decode` acepta las dos
  formas y traduce la vieja al vuelo; nadie reautoriza los efectos de una mina.
  La traducción es de ida solo: volver a escribir la forma vieja ataría cada
  efecto otra vez a los ocho tipos que conocía.
- **Una condición rota se avisa una vez, no una por play.** Un efecto de mina se
  dispara miles de veces.

### Comandos — siempre Lamp, nunca un executor a mano

Todo comando se escribe con **Lamp** (`io.github.revxrsal:lamp.*`), la base
del ecosistema: `compileOnly` en Gradle y `libraries:` en el plugin.yml para
que el servidor la descargue de Maven Central. Nunca `onCommand`, ni un
`CommandExecutor` propio, ni otra versión que la del resto de plugins.

### Reload — cada cual recarga lo suyo

- **No hay sistema de reload.** `Configs.reloadAll(plugin)` + `onReload` lo
  cubren; un plugin se recarga a sí mismo en tres líneas y nunca toca la lib.
- **`/exylialib reload` recarga los cinco ficheros de la lib** (`config.yml`,
  `colors.yml`, `formats.yml`, `economy.yml`, `input.yml`) y nada de un
  consumidor. La paleta sola basta para recolorear todo el servidor:
  `Colors.apply` → se descarta la caché de `TextEngine` → `BoardManager` y
  `HologramRuntime` se re-envían enteros. `reloadPalette()` conserva el nombre
  de cuando la paleta era el único fichero.
- **`info` y `stats` no añaden contadores**, solo enseñan lo que los módulos
  ya exponen (`Effects.active()`, `Databases.registered()`, …) y lo que Bukkit
  ya sabe (quién declara `ExyliaLib` en su `plugin.yml`). Un diagnóstico que
  obliga a instrumentar la lib deja de ser un diagnóstico.
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
    `HologramRuntime`, `EffectRuntime`, `ItemCache`.
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
- **Una línea es `[Plugin] [WARN] mensaje`.** El nombre va en degradado
  `{secondary}`→`{primary}`→`{secondary}` y la etiqueta la elige el método,
  nunca el llamante: son las mismas cinco cosas, no cuatro ejes nuevos.
- **El color sale de la paleta del servidor** y el mensaje se anexa literal:
  una traza llena de `&` y `{}` sale tal cual.
- **El degradado se lee en cada línea, no se cachea.** Por eso este módulo
  responde al reload sin `invalidateAll()`, y por eso no está en
  `ExyliaLib.loadPalette`. Un nombre de plugin son doce caracteres y un log
  no es ruta caliente: cachear no compraría nada y pagaría el acoplamiento.
- **De quién es la línea lo dice el argumento, no el método.** `Debug.of` con
  el plugin del consumidor cuando el problema es suyo (su `database.yml`, su
  menú ilegible), con la lib cuando es de la lib. Commons partía cada tipo en
  `logPluginX`/`logLibX` con el prefijo de la lib hardcodeado: eso le
  preguntaba al llamante en qué jar estaba, que es justo lo que nadie
  pregunta al leer una consola, y se elegía mal en silencio.
- **`debug()` solo imprime con `enabled(true)`**; el toggle lo da la config
  del plugin. El resto siempre imprime.
- **El banner (`motd()`) es el nombre en ASCII art**, enmarcado por una línea
  en blanco a cada lado y cerrado con versión + estado de debug + el enlace
  de Exylia: el marco de commons, que un banner encajado entre el ruido de
  arranque de otros dos plugins no tiene. jfiglet va shadeado y relocado,
  fuera del POM.
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
- **El `database.yml` es el de ExyliaCommons**: bloque por motor bajo
  `database:`, mismos nombres de clave. Un servidor que ya corre plugins de
  commons conserva sus credenciales sin tocar el fichero. Se declaran **solo**
  las claves que la lib honra; `server-id`, `write-behind`, `cache`, `redis` y
  el resto de `settings` se podan y se reportan, porque un ajuste que ya no
  hace lo que dice es peor que ninguno.
- **Un bloque de ajustes no es un valor.** `Coercions` rechaza una sección
  antes de intentar convertirla; sin eso `String.valueOf` escribía
  `MemorySection[path='database', root='YamlConfiguration']` en el fichero como
  si lo hubiera tecleado el dueño, y la poda se llevaba su contraseña de MySQL
  detrás. No era un bug de `database`: afectaba a cualquier campo `String` de
  cualquier config.
- **El layout plano de 1.24–1.30 migra**, y sus campos de conexión caen en el
  bloque que nombra su `type`, no en `mysql` siempre.
- **Una columna que la tabla exige y ningún record declara deja de exigirse.**
  Toda entidad de commons heredaba `created_at`/`updated_at` de `Entity`, y su
  `CREATE TABLE` los escribía `NOT NULL` sin default. Ningún record de la lib
  los declara, así que el primer insert tras migrar nombra menos columnas de
  las que la tabla pide y la fila se rechaza. Se afloja, no se borra ni se
  rellena: borrar se lleva los valores de las filas que un plugin sin migrar
  todavía lee, y rellenar inventa una fecha de creación que no lo es. Solo las
  que no tienen default ni valor generado; si el motor rechaza el `ALTER`, se
  deja como estaba antes que impedir el arranque.
- **La clave la trae la fila; el contador es la excepción.** Un `UUID` de
  jugador ya identifica su fila y no necesita un número más. `@Id(generated)`
  es para lo que no tiene identidad hasta que existe (un diseño en una
  biblioteca compartida, una entrada de auditoría), y obliga a `insert` en vez
  de `save`: un upsert necesita contra qué fila fusionar, y la clave de una
  fila que aún no existe es un placeholder — fusionar contra el cero pisa la
  fila que tenga ese id.
- **Pero un placeholder deja de serlo en cuanto la fila existe.** Una fila
  leída de la base de datos trae la clave que el motor eligió y nombra una
  sola fila, así que se reescribe con `update`. Sin él, una tabla con clave
  generada se podía insertar y no volver a modificar nunca: `save` la
  rechazaba y `insert` publicaba un duplicado. `update` **nunca crea** — una
  clave que no encuentra nada no cambia nada, porque la fila que crearía
  tendría otra clave distinta de la que el llamante tiene en la mano.
- **La clave se lee del mismo statement que escribió la fila**
  (`getGeneratedKeys`). Un `SELECT MAX(id)` o un `LAST_INSERT_ID()` después
  salen por otra conexión del pool, y en una tabla que escriben dos servidores
  el número es del que insertó último. `count()+1` y `MAX(id)+1` además
  reciclan la clave de una fila borrada: lo que guardó el id viejo pasa a
  apuntar a la fila de otro.
- **Cada motor lo escribe a su manera y ninguno se inventa aquí**:
  `AUTO_INCREMENT` en H2, MySQL y MariaDB; `GENERATED BY DEFAULT AS IDENTITY`
  en Postgres (nunca `SERIAL`: deja la secuencia viva al tirar la columna); y
  Mongo, que no tiene contador, lleva uno por tabla en `exylia_sequences` con
  un `$inc` atómico.
- **`saveAll` no acepta claves generadas.** Un batch no puede contestar con las
  claves que le dieron, y quien insertó cien filas sin enterarse de sus ids ha
  guardado cien filas que nadie puede referenciar.

### Redis — caché compartida, nunca almacenamiento

Todo lo que dependa de Redis pasa por `net.exylia.lib.redis`. Ningún plugin
llama nada: se enciende desde `database.yml` y los repositorios que ya tenía
empiezan a responder desde Redis y a avisar a los demás servidores.

- **Se guarda y después se avisa, nunca al revés.** Un peer que recibe el
  mensaje re-lee al instante; si el mensaje pudiera adelantar al valor,
  cachearía justo la fila que le dijeron que tirara. Es la regla de la que
  depende todo el módulo, y tiene test que la detecta invirtiéndola.
- **El join no espera ningún mensaje.** Un proxy mueve a un jugador entre
  servidores dentro del mismo tick. El servidor destino falla en su memoria
  (el jugador no estaba) y lee de Redis, donde el otro ya escribió. El pub/sub
  solo ahorra trabajo a los que ya tenían la fila. Hacerlo depender del mensaje
  convierte el handoff en una carrera que se pierde a veces — eso es
  exactamente "se me reseteó el killeffect al cambiar de servidor".
- **La base de datos es la verdad.** Toda escritura completa contra ella
  *antes* de cachear nada. Perder Redis cuesta velocidad y frescura entre
  servidores, jamás datos.
- **Solo se cachea lo que tiene clave.** `find` y `exists` sí; `select` y
  `count` no. Un leaderboard cambia cuando cambia cualquiera y ninguna clave lo
  predice. Commons los cacheaba y lo pagaba tirando el keyspace entero de la
  tabla en cada save.
- **No se cachea la ausencia.** Un primer join escribe justo esa fila un
  instante después.
- **Un `set` que falla no se anuncia.** Mandar a los peers a buscar un valor
  que no se guardó convierte una escritura fallida en un fallback a la DB de
  toda la red durante el TTL entero.
- **El valor se codifica como lo codifica la DB**, vía `EntityModel`. Commons
  cacheaba con Gson pelado mientras escribía con sus serializers: el mismo
  campo tenía dos representaciones.
- **La clave lleva el nombre de la tabla y el id *almacenado*.** Media docena
  de plugins declaran un `PlayerData`; y un `UUID` con `toString()` de un lado
  y su codec del otro da una caché que nunca acierta y parece sana.
- **`server-id` es el del config, no un UUID aleatorio.** En commons se
  regeneraba en cada arranque: una colisión dejaba dos servidores ignorándose
  para siempre y ningún log podía nombrar al emisor.
- **Un Redis caído falla rápido, no cuelga.** El `maxWait` del pool está
  acotado; commons lo dejaba en el default (infinito) y una caída se
  convertía en hilos parados.
- **Jedis vive confinado en `JedisClient`.** Verificado en bytecode. Un
  servidor sin la librería no carga esa clase y todo sigue funcionando.
- **No hay `@PlayerSession` ni flush-on-quit.** En commons era código muerto
  (cero entidades anotadas en todo el ecosistema) y no era lo que hacía
  funcionar el handoff. Aquí las escrituras son durables al completar.

### Transfer — el fichero es una línea por fila, y el resultado tiene tres valores

Mover la base de datos de un plugin a otra máquina o a otro motor pasa por
`net.exylia.lib.database.transfer`. Nunca un `mysqldump` a mano, ni un JSON
gigante escrito por el plugin.

- **Un fichero NDJSON en gzip, no un objeto anidado.** Commons escribía
  `{tables:{t:[...]}}`: un parser solo puede aceptarlo o rechazarlo entero, así
  que un dump cortado por un disco lleno no valía nada. Una línea por valor
  además deja **nombrar la línea que falló** — que es lo que un operador puede
  abrir.
- **El resultado son tres valores, no un booleano.** `PARTIAL` existe porque el
  importador de commons registraba un lote fallido, seguía, y devolvía
  `success(true)`: perder mil filas y no perder ninguna eran la misma respuesta.
  Una tabla saltada, una columna que ya no existe o una fila rechazada bajan a
  `PARTIAL` y nunca vuelven a `SUCCESS`.
- **Los valores se escriben tipados, jamás inferidos.** Gson sin type token
  convierte todo número en `Double` — eso hacía commons — así que cada `long`
  por encima de 2^53 y cada decimal volvían cambiados en silencio. El
  `BigDecimal` va como **string**: el texto *es* el valor, y el dinero es la
  única razón de que una columna sea uno.
- **`force` fusiona, no reemplaza**, y la frase se escribe entera donde se
  ofrece. La fila cuya clave está en el dump se pisa; la que no está se queda.
  Quien lo lee como "reemplaza" y lo ejecuta ha mezclado dos servidores en una
  tabla sin que nada lo diga.
- **Las filas se enlazan por nombre de columna**, usando el layout de la
  cabecera. Un record que ganó un componente desde el dump tiene que poder
  importar; enlazar por posición metería el `UUID` en la columna del clan y
  reportaría éxito.
- **Después de importar ids explícitos se mueve el contador.** H2 y Postgres no
  lo avanzan solo, así que el siguiente insert pide una clave que las filas
  importadas ya tienen. Tiene test, y el test falla con la colisión real cuando
  se quita la llamada.
- **Nunca corre un codec.** Las filas viajan en forma de almacenamiento, así que
  un inventario serializado es texto Base64 en los dos extremos y el módulo se
  testea sin servidor.
- **Un plugin aparece cuando pide su primer repositorio, no antes.** Uno que
  registra tarde exporta menos tablas de las que tiene, y desde fuera no se
  distingue: por eso se **nombran** las tablas encontradas y no solo se cuentan.
- **Deuda declarada: `writeRows` no invalida Redis.** A propósito — un mensaje
  por lote mandaría a cada peer a la base de datos por la tabla entera, que es
  como se hundió commons. Importar sobre una tabla **viva** con Redis deja a los
  demás servidores sirviendo filas viejas hasta el TTL; sobre una tabla nueva
  (el caso de migración) no aplica. El comando avisa justo en ese caso.

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
| config | `config/Configs`, `ConfigFile`, `MutableConfig`, `Key`, `Comment`, `Migration`, `ConfigIssue`, `Schema` (1.50.0) | `config/internal/` (+ `SchemaProjection`) | [docs/config.md](docs/config.md) | 1.1.0 |
| text | `text/Text`, `Colors`, `Palette`, `Lines` (1.48.0) | `text/internal/` | [docs/text.md](docs/text.md) | 1.2.0 |
| placeholder | `placeholder/Placeholders`, `Template`, `Resolver`, `Request` | `placeholder/internal/` | [docs/placeholders.md](docs/placeholders.md) | 1.3.0 |
| effect | `effect/Effects`, `Timer`, `Ticks`, `Display`, `EffectConfig` | `effect/internal/` | [docs/effects.md](docs/effects.md) | 1.4.0 |
| scoreboard | `scoreboard/Scoreboards`, `Board`, `SidebarConfig` | `scoreboard/internal/` | [docs/scoreboard.md](docs/scoreboard.md) | 1.5.0 |
| hologram | `hologram/Holograms`, `Hologram`, `HologramConfig` | `hologram/internal/` | [docs/hologram.md](docs/hologram.md) | 1.6.0 |
| client | `client/Clients`, `PluginClients`, `Waypoint`, `Cooldown`, `ClientBrand`, `ClientTeam`, `PluginTeams` | `client/internal/` (+ `TeamRegistry`) | [docs/client.md](docs/client.md) | 1.7.0 (equipos 1.36.0, dueño 1.48.0) |
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
| dueño de efectos por plugin | `effect/Effects.of`, `PluginEffects` | `effect/internal/EffectRuntime` (registro por plugin) | [docs/effects.md](docs/effects.md) | 1.18.3 |
| skull | `skull/Skulls`, `SkullSource`, `SkullBuilder`, `SkullHandle` | `skull/internal/` | [docs/skulls.md](docs/skulls.md) | 1.19.0 |
| action | `action/Actions`, `PluginActions`, `ActionCall`, `ActionContext`, `ActionSequence` y tipos auxiliares | `action/internal/` | [docs/actions.md](docs/actions.md) | 1.20.0 |
| region | `region/Regions`, `PluginRegions`, `RegionSnapshot`, `RegionShape` y formas, `PolicyKey`/`PolicySet`, `RegionData`/`RegionCodec`, `PlayerRegionChangeEvent` (filtro por dueño 1.48.0), selección y visualización | `region/internal/` | [docs/regions.md](docs/regions.md) | 1.23.0 |
| lo que un jugador debe ver al volver | `client/Clients.Waypoints.restoreWith` | `client/internal/ClientRuntime.restore` (RESTORERS por dueño) | [docs/client.md](docs/client.md) | 1.58.0 |
| escribir en los slots editables de un menú | `ui/UiSession.input`, `inputs(Map)` | `ui/internal/Session.requireInput` | [docs/menus.md](docs/menus.md) | 1.58.0 |
| dibujar un icono guardado | `item/Items.icon` | `item/internal/ItemRenderer.icon` (una sola copia: `util/editor/internal/Icons.base` delega) | [docs/items.md](docs/items.md) | 1.58.0 |
| limpiar entidades de una región | `region/PluginRegions.clearEntities` (con y sin predicado) | `region/internal/RegionEntities` | [docs/regions.md](docs/regions.md) | 1.58.0 |
| selector como el de commons | `region/SelectionOptions` (builder), `SelectionState.AWAITING_CONFIRMATION`, `SelectionSession.confirm` | `region/internal/SelectorWand`, `SelectionPreview`, `SelectionRuntime`, `SelectionListener` | [docs/regions.md](docs/regions.md) | 1.56.0 |
| database | `database/Databases`, `PluginDatabase`, `Repository`, `Query`, `Table`, `Column`, `Id`, `Indexed`, `Index`, `Codec`, `DatabaseException`, `DatabaseSettings` | `database/internal/` | [docs/database.md](docs/database.md) | 1.24.0 |
| format | `format/Formats`, `Numbers`, `Amounts`, `Dates`, `FormatSettings`; `util/TimeFormats` | `format/internal/` | [docs/formats.md](docs/formats.md) | 1.25.0 |
| economy | `economy/Economy`, `CurrencyProvider`, `EconomyResponse`, `TransferResult`, `EconomySettings`, `EconomyException` | `economy/internal/` | [docs/economy.md](docs/economy.md) | 1.26.0 |
| input | `input/Inputs`, `PluginInputs`, `InputRequest` y tipos por valor, `ChoiceInput`, `SearchInput`, `FormInput`, `FormField`, `FormKey`, `FormValues`, `InputResult`, `InputOutcome`, `Validation`, `InputParser`, `InputException`, `InputSettings` | `input/internal/` | [docs/input.md](docs/input.md) | 1.31.0 |
| command | `command/Commands`, `PluginCommands`, `CommandLine`, `CommandActor`, `CommandResult` | — | — | 1.21.0 |
| item | `item/Items`, `PluginItems`, `Item`, `Source`, `Appearance`, `Traits`, `Potion`, `Trim`, `Banner`, `Consumable`, `Modifier`, `Problems` | `item/internal/` | [docs/items.md](docs/items.md) | 1.22.0 |
| ui | `ui/Menus`, `PluginMenus`, `UiSession`, `UiDefinition`, `UiSection`, `UiEntry`, `UiItem`, `UiKeys`, `UiFillers`, `UiRefresh`, `UiSounds`, `UiAnimationSpec`, `ClickBindings`, `ClickKind`, `ClickPolicy`, `Pages`, `Slots` | `ui/internal/` | [docs/menus.md](docs/menus.md) | 1.22.0 |
| valores de fila con formato | `ui/UiEntry.Builder.withFormatted`; `item/PluginItems.render(item, viewer, values, formatted)` | `item/internal/ItemRenderer.text` | [docs/menus.md](docs/menus.md), [docs/items.md](docs/items.md) | 1.28.0 |
| small text | `small-text` en `internal/LibrarySettings`; medida en `text/Centering` | `text/internal/SmallText`, `TextEngine.smallText` | [docs/text.md](docs/text.md) | 1.29.0 |
| util (sequence) | `util/sequence/Sequences`, `PluginSequences`, `Sequence`, `SequenceTarget`, `SequenceRun`, `SequenceStep`, `Shape` | `util/sequence/internal/` | [docs/sequences.md](docs/sequences.md) | 1.30.0 |
| efectos con dado, condición y público | `util/sequence/EffectEntry`, `EffectCodec`, `PluginSequences.play(List, target)`/`editor` | `util/sequence/internal/EffectPlayer`, `util/sequence/EffectDescriptor`; `[MESSAGE]` en `SequenceCompiler` | [docs/sequences.md](docs/sequences.md) | 1.57.0 |
| condiciones compartidas | — | `util/internal/Conditions` (movido desde `util/reward/internal`) | [docs/rewards.md](docs/rewards.md) | 1.57.0 |
| util (preview) | `util/preview/Previews`, `PluginPreviews`, `Preview`, `PreviewSettings` | `util/preview/internal/` | [docs/previews.md](docs/previews.md) | 1.30.0 |
| redis | `redis/Redis`, `RedisSettings` | `redis/internal/` (Jedis confinado en `JedisClient`) | [docs/redis.md](docs/redis.md) | 1.31.0 |
| poll de auto-actualización | `update-check-minutes` en `internal/LibrarySettings` | `internal/ExyliaLibUpdater` (ETag), timer en `ExyliaLib.startUpdateCheck` | [docs/reload.md](docs/reload.md) | 1.30.0 |
| claves generadas | `database/Id.generated`, `Repository.insert`/`insertReturning` | `Dialect.insertGenerated`, `SqlBackend.insert` (`getGeneratedKeys`), `MongoBackend.insert` (`$inc`), `EntityModel.withId` | [docs/database.md](docs/database.md) | 1.32.0 |
| clic que redibuja todo lo que puede cambiar | — | `ui/internal/Session.refreshAfterClick`, `redrawChangeable` | [docs/menus.md](docs/menus.md) | 1.44.0 |
| bloque que el tipo de ítem escribe solo | `hide-attributes` (mismo fichero) | `item/internal/ItemComponents` (registro + `TooltipDisplay` por reflexión), `ItemRenderer.hideAdditionalTooltip` | [docs/items.md](docs/items.md) | 1.46.0, 1.47.0 |
| modificar una fila con clave generada | `database/Repository.update` | `Dialect.update`, `SqlBackend.update`, `MongoBackend.update` (sin upsert), `EntityModel.hasPlaceholderId`, `CachedStorage.update` | [docs/database.md](docs/database.md) | 1.43.0 |
| util (rewards) | `util/reward/Rewards`, `PluginRewards`, `RewardEntry`, `RewardType`, `RewardCodec`, `RewardResult`, `RewardDelivery`, `RewardOutcome`, `OverflowPolicy`, `PendingRewards` | `util/reward/internal/` (`Providers`, `ItemGiver`, `Conditions`, `Rolls`), `util/reward/Previews` | [docs/rewards.md](docs/rewards.md) | 1.34.0 |
| util (snapshots) | `util/snapshot/Snapshots`, `PluginSnapshots`, `Snapshot`, `SnapshotPart`, `SnapshotCodec`, `SnapshotSettings` | `util/snapshot/internal/` (`PlayerState`, `SnapshotRow`, `LegacyRow`, `LegacyImport`, `SnapshotRuntime`) | [docs/snapshots.md](docs/snapshots.md) | 1.34.0 |
| util (teleport) | `util/teleport/Teleports`, `PluginTeleports`, `TeleportRequest`, `TeleportHandle`, `TeleportResult`, `TeleportCause`, `TeleportSettings`, `ExyliaLocation`, `ExyliaTeleportEvent`, `RandomArea`, `TeleportDirection`, `TeleportRequestTicket`, `TpaAcceptance`, `TpaOutcome` | `util/teleport/internal/` (`TeleportRuntime`, `RunningTeleport`, `TeleportPlan`, `Teleporter`, `SafeLocations`, `RandomLocations`, `BackHistory`, `TpaBook`, `CrossServer`) | [docs/teleport.md](docs/teleport.md) | 1.34.0 |
| util (wizard) | `util/wizard/Wizards`, `PluginWizards`, `Wizard`, `WizardBuilder` (+ `Branch`), `WizardStep` (+ `Prompt`), `WizardKey`, `WizardValues`, `WizardRun`, `WizardOutcome`, `WizardResult`, `WizardSettings`, `WizardException` | `util/wizard/internal/` (`WizardRuntime`, `WizardSession`, `WizardListener`); `init`/`forget`/`release` en `ExyliaLib` | [docs/wizard.md](docs/wizard.md) | 1.34.0 |
| consola con look de commons | `debug/Debug` (degradado, etiqueta por tipo, marco del `motd`) | `gradientName`/`blend` en `Debug` | [docs/debug.md](docs/debug.md) | 1.35.0 |
| util (world) | `util/world/Worlds` | `util/world/internal/` (`WorldsBackend`, `WorldsBackendDetector`, `WorldsReflection`, `Worlds3Backend`, `Worlds4Backend`) | [docs/world.md](docs/world.md) | 1.36.0 |
| nametag | `nametag/Nametags`, `PluginNametags`, `NametagStyle` | `nametag/internal/` (`NametagRuntime`, `State`, `NametagSink`; PacketEvents confinado en `NametagPackets`) | [docs/nametags.md](docs/nametags.md) | 1.36.0 |
| util (combat) | `util/combat/Combat`, `CombatBridge`, `CombatStats` | `util/combat/internal/` (`CombatRuntime`, `CombatProvider`, `DeluxeCombatProvider`, `PvpManagerProvider`) | [docs/combat.md](docs/combat.md) | 1.36.0 |
| transfer | `database/transfer/Transfers`, `PluginTransfers`, `TransferReport`, `TableTransfer`, `TransferOutcome` | `database/transfer/internal/` (`DumpFormat`, `DumpWriter`, `DumpReader`, `DumpException`, `TransferRuntime`, `DumpFormatAccess`); comando en `internal/ReloadCommand` sobre `internal/TransferAccess` | [docs/transfer.md](docs/transfer.md) | 1.36.0 |
| `/exylialib info` y `stats` | — | `internal/ReloadCommand` (`dependentsOf`, `hologramsLine`) | [docs/reload.md](docs/reload.md) | 1.35.0 |
| `/exylialib export` e `import` | — | `internal/ReloadCommand` (`export`, `importDump`, `reportPanel`, `importPanel`, `safeName`, `KnownPlugins`) | [docs/transfer.md](docs/transfer.md) | 1.36.0 |
| banner por jugador | `item/Banner.template`, `Banner.isDynamic` | `item/internal/ItemReader.banner`, `TraitApplier.resolved` | [docs/items.md](docs/items.md) | 1.37.0 |
| contexto parseado y título paginado | — | `ui/internal/Session.parsed`, `merged`, `filledTitle` | [docs/menus.md](docs/menus.md) | 1.39.0 |
| título que sigue a la página | — | `ui/internal/Session.retitle`, `Titles`, `TitlePackets` (PacketEvents confinado) | [docs/menus.md](docs/menus.md) | 1.40.0 |
| flecha de `navigation` que pagina sola | — | `ui/internal/MenuLoader.placed` (fallback por sección) | [docs/menus.md](docs/menus.md) | 1.41.0 |
| columna heredada de commons que la tabla exige | — | `database/internal/SqlSchema.relaxOrphanedColumns`, `Dialect.dropNotNull`, `SchemaReport.relaxedColumns` | [docs/database.md](docs/database.md) | 1.42.0 |
| valor de fila multilínea | `<nl>` en un valor de `UiEntry`/`PluginItems.render` | `item/internal/ItemRenderer.lore`, `spans`, `segment` | [docs/menus.md](docs/menus.md), [docs/items.md](docs/items.md) | 1.38.0 |
| schematic | `schematic/Schematics`, `PluginSchematics`, `SchematicResult`, `SchematicOutcome`, `RegenerateOptions` | `schematic/internal/` (`SchematicRuntime`, `SchematicStore`, `SchematicNames`, `Bounds`, `Engines`, `SchematicEngine`; FAWE confinado en `FaweEngine`) | [docs/schematics.md](docs/schematics.md) | 1.48.0 |
| util (loot) | `util/loot/Loot`, `LootEntry`, `LootType`, `LootCodec` | `util/loot/internal/` (`LootLines`, `LootRolls`, `LootItems`) | [docs/loot.md](docs/loot.md) | 1.56.0 |
| util (editor) | `util/editor/Editors`, `PluginEditors`, `ListEditor`, `EditorDescriptor`, `EditorForm`, `Clipboard`, `IconPicker`, `Pickers` | `util/editor/internal/` (`EditorRuntime`, `EditorHolder`, `EditorListener`, `InsertWindow`, `Icons`) | [docs/editors.md](docs/editors.md) | 1.56.0 |
| botones propios en un editor | `util/editor/EditorButton`, `EditorView`, `ListEditor.button` | `util/editor/internal/EditorHolder` (banda y tamaño de página) | [docs/editors.md](docs/editors.md) | 1.58.0 |
| util (named commands) | `util/command/NamedCommand`, `NamedCommands` | `util/command/NamedCommandDescriptor` | [docs/editors.md](docs/editors.md) | 1.56.0 |
| editores incluidos | `PluginRewards.editor`, `Loot.editor`, `NamedCommands.editor`, `Effects.editor`, `PluginEditors.items`/`locations`/`pick`/`icon` | `util/reward/RewardDescriptor`, `util/loot/LootDescriptor`, `util/PotionEffectDescriptor`, `util/editor/ItemListEditor`, `LocationDescriptor` | [docs/editors.md](docs/editors.md) | 1.56.0 |
| diálogo alto y prerellenado | `input/TextInput.lines`, `FormField.lines` | `input/internal/DialogPackets.multiline` | [docs/input.md](docs/input.md) | 1.56.0 |

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
| `item/internal/ItemRenderer` | `components(...)` (quién escribe los data components: `DataComponentTypes` exige un servidor vivo solo con nombrarla) |
| `item/internal/ItemComponents` | `forgetReportedForTests` (el aviso ya dicho, que es una vez por servidor) |
| `skull/internal/SkullRuntime` | `installForTests` (lookup y store), `seed` (textura sin red) |
| `skull/internal/Lookup` | la interfaz que sustituye a Mojang en tests |
| `util/snapshot/SnapshotCodec` | `setItems/resetItems` (`ItemIo`: cómo un ítem se vuelve texto — un `ItemStack` real no se construye sin servidor) |
| `util/snapshot/internal/SnapshotRuntime` | `forgetReportedForTests` (los avisos ya dichos) |
| `database/transfer/internal/DumpFormatAccess` | `extension()`, `observeBatches` (los lotes que el lector entrega: la cota de memoria del import, observable) |
| `internal/TransferAccess` | la interfaz que el comando usa para exportar e importar; `live()` es la real, un fake la sustituye sin base de datos ni fichero |
| `schematic/internal/Engines` | `install(...)` (el motor: un fake sustituye a FastAsyncWorldEdit, así que **todo** lo que decide el módulo — nombre, carpeta, orden de las etapas, qué pasa cuando una revienta — se testea sin FAWE y sin servidor) |
| `schematic/internal/SchematicEngine` | la interfaz que se instala ahí; su única implementación real es la que nombra FAWE |
| `util/sequence/internal/EffectPlayer` | `forgetReportedForTests` (los avisos ya dados y las secuencias compiladas: "una vez" solo se puede afirmar dos veces si se puede olvidar) |
| `util/editor/internal/EditorHolder` | package-private entero: la copia de trabajo, las páginas y el final único se prueban sin ventana, que es la única parte que exige servidor |
| `region/internal/SelectionRuntime` | `installWand/resetWand` (cómo el selector llega al jugador: construir un `ItemStack` resuelve el registro de ítems, que ningún entorno de test tiene) |
| `util/loot/internal/LootRolls` | `Dice` (los dados: tirada, rango y barajado). El resto del módulo decide sobre strings y números, así que con esta costura toda la lógica de una tabla de loot se prueba sin azar |
| `util/loot/internal/LootItems` | la interfaz que construye el `ItemStack` — la única parte del módulo que necesita servidor; un doble la sustituye y la gramática escrita se prueba entera sin registro |
| tests compartidos | `src/test/java/net/exylia/lib/FakeServer.java`, `FakePlayer.java`, `debug/DebugCapture.java`; `FakeServer.runAsyncForReal()` ejecuta las async en un hilo real |

**Los fakes no son gratis, y un benchmark que los llama se mide a sí mismo.**
`FakePlayer` es un `java.lang.reflect.Proxy` y `FakeServer.newWorld` recalcula
su UUID con un MD5 en **cada** `getUID()`. Medir a través de ellos infló el
primer `RegionBenchmark` en ~460 bytes por move que no eran de la lib. Un
benchmark saca del bucle medido todo lo que el servidor real no recalcula, e
imprime el piso del harness para que el número se pueda leer como "esto más
aquello" en vez de culpar a la lib.

### Release protocol (summary; details are in *Verification*)

1. Run `./gradlew clean build` and require a green build with zero warnings.
2. Run tests and sabotage checks: deliberately break the logic and verify that
   the relevant test fails.
3. `publishToMavenLocal` is local-only validation. Use it to compile an external
   consumer against the local artifact; it does not publish to GitHub.
4. Update the module documentation (see the rules) and README when applicable.
5. Contributors and agents must not manually create GitHub tags or releases,
   edit or publish `lib-manifest.json` for a release, or run release commands.
   Commit and push only the files belonging to their own completed change.
6. Changing `version` in `build.gradle` is intentional release input. Coordinate
   before changing it: a push to `main` with a new strict `X.Y.Z` version signals
   `.github/workflows/release.yml` to build and test, create the `v<version>`
   GitHub release with the JAR, update `lib-manifest.json`, and push the manifest
   with the bot account. The workflow rejects duplicate, downgrade, and existing
   tag versions.
7. Keep local release-readiness checks separate from GitHub publication. Verify
   the generated JAR and, when relevant, its POM and downloaded release checksum
   only after the workflow has completed.
8. In consumer plugins: update `compileOnly("net.exylia:ExyliaLib:1.x.y")`, adapt
   code for API changes, run `./gradlew build`, commit, and **deploy the plugin
   JAR manually** — consumer plugins have no auto-updater. If a plugin uses a new
   API, the library JAR must reach the server **before or together with**
   the plugin JAR (`NoSuchMethodError` otherwise).

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
