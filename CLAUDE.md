# MoonEye

Detekce selhání 3D tisku pro Klipper/Moonraker. Java 25 + Javalin + H2/JDBI + ONNX Runtime,
jeden kontejner (podman). Nástupce YoloRakeru (`../YoloRaker`). Ten skončil, protože jeho model
(YOLO11n z malého Kaggle datasetu) nezachytil ani uměle vyvolané chyby.

Zdrojáky a komentáře anglicky, tenhle soubor česky.

---

## Build a testy

```bash
export JAVA_HOME=/home/krob/NetBeansJDKs/jdk-25.0.5_linux-x64_bin.tar/jdk-25.0.5
export PATH="$JAVA_HOME/bin:/usr/lib/apache-netbeans/java/maven/bin:$PATH"
mvn package                     # build + JUnit testy -> target/mooneye.jar
mvn test -Dmooneye.model=<cesta k .onnx>   # navíc test se skutečným modelem
```

Systémová `java` je 21, takže pro build je nutné JDK 25 výše. `git` je k dispozici. Podman na
vývojovém stroji není, image se tady sestavit nedá.

Model (~200 MB) není v repozitáři. Aplikace si ho při prvním startu stáhne z obico.io
(SHA-256 je ověřený) do `$MOONEYE_DATA_PATH/models/`.

**Nikdy nespouštěj aplikaci proti `./data`.** Použij `MOONEYE_DATA_PATH` ve scratchpadu,
`MOONEYE_PORT=18080` a `MOONEYE_MODEL_PATH` na už stažený model.

---

## Architektura

```
PollingTelemetrySource  1 Hz  -> TelemetryAggregator / TelemetryService (cache, okna 10 s)
PrintMonitor           10 s   -> job tracking -> snímek -> FailureDetector -> FailurePredictor -> akce
RetentionService        1 h   -> mazání starých tisků
ApiServer                     -> REST API + statické UI (src/main/resources/public)
```

- `detection/FailureDetector`: ONNX inference, model Obico gen1 (YOLOv2, třída `failure`, vstup
  416×416). Export už obsahuje dekódované boxy, zbývá práh 0,08 a NMS 0,45.
- `detection/FailurePredictor`: věrný port Obico `lib/prediction.py` + `failure_detection.py`.
  Parametry v `PredictionParams.OBICO_GEN1`.
- `core/PrintMonitor`: orchestrace a veškerá „politika" (kdy varovat, kdy pauzovat).
- `tools/Evaluate`: offline běh modelu nad obrázky (bez tiskárny a serveru).

---

## Invarianty

1. **Algoritmus a parametry Obico neupravovat „na oko".** Prahy jsou fitované přesně na tuhle
   aritmetiku, na tenhle model a na **10 s kadenci**. Změna cyklu nebo vzorce je zneplatní.
   Změnu dělej jen na základě dat (detection_log + review incidentů).
2. **Předzpracování obraz roztahuje, nedělá letterbox.** Tak to dělá Obico a na tom byl model
   laděný. U YoloRakeru byl chybějící letterbox chyba, tady je roztažení správně.
3. **Pauza nekončí job.** Jeden fyzický tisk = jeden řádek v `print_jobs`. Job uzavře jen
   terminální stav Klipperu (complete/cancelled/error/standby) nebo nový tisk (jiný soubor,
   případně přetočené `print_duration`).
4. **Po resume z pauzy, kterou způsobil MoonEye**, se resetuje stav aktuálního tisku
   (`resetForNewPrint`, znovu 5 min warm-up). Dlouhodobý průměr tiskárny zůstává.
5. **Varování max. 1× na tisk.** Selhání v režimu NOTIFY také max. 1×. V režimu PAUSE hlídá
   opakování samotná pauza + warm-up po resume.
6. **`rolling_mean_long` je per tiskárna a persistentní** (tabulka `predictions`). Resetuje se
   při změně URL kamery nebo ROI, případně ručně přes `reset-baseline`.
7. **Žádná autentizace.** Aplikace je pro domácí síť (jako Mainsail/Fluidd), uživatel přihlašování
   výslovně nechce. Přístup zvenku = reverzní proxy s loginem, ne login v aplikaci.
8. **Schéma DB se mění jen přidáním migrace** do `db/Migrations`. Existující migrace se nikdy
   neupravují.

---

## Stav

- Backend je hotový a otestovaný (JUnit: predictor, detektor, DB, monitor s falešným
  Moonrakerem/kamerou, API).
- **UI je hotové** (česky, vanilla JS moduly bez build kroku, `src/main/resources/public/`):
  záhlaví s menu ☰ (přidat tiskárnu, nastavení, události, vzhled světlý/tmavý/systém, o aplikaci),
  karty tiskáren s ukazatelem rizika, panel úpravy tiskárny se záložkami a výběrem ROI. Uživatel
  ho schválil. Chybí jen zobrazení historie tisků (položka v menu karty je zatím neaktivní).
- Na UI se pracuje přes `DevServer` (src/test): simulované tiskárny + falešná kamera, port 8090.
  Prohlížečové nástroje v sezení nejsou, vzhled kontroluje uživatel.
- Na reálné tiskárně zatím neověřeno. Další krok: snímky z kamery s nachystanou chybou
  -> `tools/Evaluate`, případně `POST /api/printers/{id}/analyse`.
