# MoonEye

*🌐 [English](README.md) | Čeština*

**3D Printer AI monitoring service pro Klipper.** MoonEye sleduje kameru tiskárny, rozpozná
selhávající tisk (spaghetti, odtržený výtisk, rostoucí chuchvalec) a přes Moonraker tisk pozastaví
dřív, než z něj vznikne vrabčí hnízdo. Běží jako jeden kontejner na vlastním hardwaru, na CPU,
bez cloudu a bez účtu.

## Vlastnosti

- **Detekce selhání pomocí AI.** Využívá open-source model a ověřený rozhodovací algoritmus
  projektu Obico, vyladěný na velkém množství skutečných tisků.
- **Pozastavit, nebo jen upozornit.** U každé tiskárny se dá zvolit, co se při selhání stane.
- **Učí se, jak záběr běžně vypadá.** Věci, které kamera vidí pořád (koš na odpad, kabely,
  texturovaná podložka), se započítají do dlouhodobé základní úrovně. Selhání vynikne, protože
  vznikne během pár minut.
- **Výřez sledované oblasti.** Stačí myší vybrat podložku a model ignoruje všechno ostatní.
- **Test bez tisku.** Jedním kliknutím se model pustí na aktuální snímek z kamery. Úhel kamery se
  tak dá vyzkoušet na nachystané chybě.
- **Přehled tiskáren.** Živá kamera s rámečky detekcí, průběh tisku, teploty a ukazatel rizika
  selhání u každé tiskárny. Světlý a tmavý režim.
- **Oznámení.** Webhook a MQTT pro Home Assistant, Node-RED, Discord a další, volitelně i zpráva
  na displeji tiskárny (M117).
- **Historie tisků.** Jeden záznam na jeden fyzický tisk, i když se mezitím pozastaví. Během tisku
  se ukládají snímky a incidenty jde označit jako skutečné selhání nebo planý poplach.
- **Offline.** Žádné CDN, žádná externí písma. Model se stáhne jen jednou, při prvním spuštění.

## Jak to funguje

1. **Každých 10 sekund**, dokud tiskárna hlásí `printing`, MoonEye vezme snímek z její kamery.
2. **Model** (detektor selhání od Obico: YOLOv2, jedna třída *failure*) vrátí rámečky kolem všeho,
   co vypadá jako selhaný tisk, a ke každému jistotu.
3. **Rozhodnutí.** Jistoty na snímku se sečtou. Rychlý klouzavý průměr tohoto součtu se porovnává
   se dvěma základními úrovněmi: jak zatím vypadal tento tisk a co kamera této tiskárny běžně vidí
   (asi 20 hodin snímků). Tisk selhává, když průměr výrazně překročí obě. Prvních 5 minut každého
   tisku se nehodnotí.
4. **Akce.** *Varování* pošle jednou za tisk upozornění. *Selhání* tisk pozastaví (nebo jen
   upozorní, pokud je tak tiskárna nastavená). Po obnovení tisku začne detekce znovu 5minutovým
   zahříváním, aby tentýž důkaz tisk hned znovu nepozastavil.

Prahy jsou převzaté přímo od Obico, vyladěné pro tento model a 10sekundový interval. MoonEye je
nemění.

Co MoonEye **nedělá**: stringing, pupínky a další povrchové vady jsou na snímku celé podložky
z webkamery příliš malé a model se o ně nepokouší.

## Začínáme

### Spuštění v podmanu

```bash
git clone https://github.com/martinkrob/mooneye.git
cd mooneye
podman-compose up -d
```

Otevři `http://<adresa>:8080`. Při prvním startu si MoonEye stáhne model (~200 MB) do
`./data/models` a ověří jeho kontrolní součet. Jakmile je detekce připravená, tečka v záhlaví
zezelená.

V Dockeru místo podmanu odstraň z `compose.yml` řádek `userns_mode` a spusť `docker compose up -d`.

### Přidání tiskárny

V menu ☰ zvol **Přidat tiskárnu**:

1. **Připojení:** adresa Moonrakeru (stačí IP nebo název, port 7125 se doplní sám) a URL
   **snímku** z kamery. S crowsnestem to bývá `http://<tiskárna>/webcam/?action=snapshot`. Obojí
   jde otestovat tlačítkem.
2. **Detekce:** co dělat při selhání, citlivost a volitelně sledovaná oblast, vybraná tažením
   myší na živém snímku.
3. **Oznámení:** webhook, MQTT a displej tiskárny.

Pak na podložku nachystej chybu (chuchvalec filamentu) a v menu ⋯ tiskárny použij
**Otestovat detekci teď**. Uvidíš, co model na tvé kameře najde.

### Konfigurace

Všechno ostatní se nastavuje v UI. Proměnné prostředí popisují jen nasazení:

| Proměnná | Výchozí | Účel |
|---|---|---|
| `MOONEYE_PORT` | `8080` | HTTP port |
| `MOONEYE_DATA_PATH` | `./data` | Databáze, model a snímky |
| `MOONEYE_DB_PATH` | `$DATA/mooneye` | Soubor databáze H2 (bez přípony) |
| `MOONEYE_MODEL_PATH` | `$DATA/models/obico-failure-gen1.onnx` | Model ONNX |
| `MOONEYE_MODEL_URL` | URL od Obico | Odkud stáhnout chybějící model; prázdné = nikdy nestahovat |
| `MOONEYE_MODEL_SHA256` | součet souboru od Obico | Očekávaný kontrolní součet staženého souboru |
| `MOONEYE_TELEMETRY_INTERVAL_MS` | `1000` | Jak často se dotazovat Moonrakeru; `0` = jen když je potřeba |

Na síti bez internetu nakopíruj model do `./data/models` ručně a nastav `MOONEYE_MODEL_URL=`
(prázdné).

Paměť: JVM s modelem potřebuje zhruba 0,5–1 GB. Na desktopovém CPU trvá jeden snímek asi 0,1 s.

### Zabezpečení

MoonEye nemá přihlašování, stejně jako Mainsail a Fluidd: je určený pro domácí síť. Pokud má být
dostupný zvenku, dej ho za reverzní proxy s přihlášením.

## Oznámení

Webhook a MQTT dostávají JSON. Selhání vypadá takto:

```json
{
  "event": "failure_detected",
  "printerId": "62bb96cd-…",
  "printerName": "Voron 2.4",
  "timestamp": "2026-11-03T12:20:27Z",
  "action": "PAUSED",
  "filename": "cube.gcode",
  "progress": 42.0,
  "failureScore": 0.81,
  "incidentId": 12
}
```

Události: `failure_warning`, `failure_detected` (akce `PAUSED` nebo `NOTIFIED`), `telemetry`
(každých 10 s, je-li zapnutá) a `test`.

## Vyzkoušení modelu na fotkách

Bez serveru a bez tiskárny. Ulož pár snímků z kamery a spusť:

```bash
java -cp target/mooneye.jar h848.software.mooneye.tools.Evaluate \
     --model data/models/obico-failure-gen1.onnx --out annotated/ snimky/
```

Nástroj vypíše jistoty pro každý obrázek a uloží kopie s nakreslenými rámečky.

| Volba | Účinek |
|---|---|
| `--roi x,y,w,h` | Analyzovat jen tuto část snímku |
| `--sequence` | Přehrát obrázky jako po sobě jdoucí snímky přes rozhodovací logiku |

## REST API

UI používá jednoduché JSON API, které je k dispozici i skriptům:

| Endpoint | Účel |
|---|---|
| `GET /api/health` | Stav, verze, zda je načtený model |
| `GET/PUT /api/settings` | Uchovávání historie, interval snímků |
| `GET/POST /api/printers`, `GET/PUT/DELETE /api/printers/{id}` | Tiskárny (API klíč a heslo k MQTT jdou jen zapsat) |
| `GET /api/printers/{id}/status` | Telemetrie, stav detekce a aktuální tisk |
| `GET /api/printers/{id}/frame` · `/camera` | Poslední analyzovaný snímek · čerstvý snímek z kamery (JPEG) |
| `POST /api/printers/{id}/analyse` | Pustit model na čerstvý snímek, nic neukládat |
| `POST /api/printers/{id}/mute` | Ztlumit upozornění do konce tisku |
| `POST /api/printers/{id}/reset-baseline` | Zapomenout běžný záběr kamery (po jejím přesunutí) |
| `GET /api/printers/{id}/jobs`, `…/jobs/{jobId}/detections`, `…/jobs/{jobId}/snapshots` | Historie tisků |
| `GET /api/printers/{id}/incidents`, `POST /api/incidents/{id}/review` | Varování a selhání, jejich hodnocení |
| `GET /api/events` | Záznam událostí |

## Vývoj

Potřeba: JDK 25 a Maven 3.9.

```bash
mvn package                                     # build a testy -> target/mooneye.jar
mvn test -Dmooneye.model=/cesta/k/modelu.onnx   # navíc test se skutečným modelem
```

`DevServer` v `src/test` spustí aplikaci proti třem simulovaným tiskárnám (tiskne, nečinná,
offline) a falešné kameře, do jejíhož obrazu jde přidat chuchvalec filamentu. Na UI se tak dá
pracovat bez hardwaru. Spusť ho z IDE a otevři `http://localhost:8090`.

Technologie: Java 25, Javalin, H2 + JDBI, ONNX Runtime, Eclipse Paho. Frontend je čisté HTML, CSS
a JavaScriptové moduly bez build kroku.

## Stav

MoonEye je mladý projekt. Detekce je pokrytá testy proti simulovaným tiskárnám a skutečnému modelu,
ale na velkém množství reálných tisků zatím ověřená není. Zkušenosti ze skutečných tiskáren jsou
vítané. Zatím chybí: zobrazení historie tisků v UI.

## Poděkování

Model a rozhodovací algoritmus pocházejí z projektu
[Obico](https://github.com/TheSpaghettiDetective/obico-server) (dříve The Spaghetti Detective).
Server Obico je pod licencí AGPL-3.0. Váhy modelu se stahují z obico.io za běhu a nejsou součástí
tohoto repozitáře ani obrazu kontejneru.
