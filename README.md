# MoonEye

*🌐 English | [Čeština](README.cs.md)*

**3D Printer AI monitoring service for Klipper.** MoonEye watches your printer's webcam,
recognises a failing print (spaghetti, a part torn off the bed, a growing blob) and pauses it
through Moonraker before it turns into a bird's nest. It runs as a single container on your own
hardware, on CPU, with no cloud service and no account.

## Features

- **AI failure detection.** Uses Obico's open-source failure model and its proven decision
  algorithm, which was tuned on a large set of real prints.
- **Pause or notify.** Choose per printer whether a failure pauses the print or only sends an alert.
- **Learns what is normal for your camera.** Things the camera always sees (a purge bucket, cables,
  a textured bed) are absorbed into a long-term baseline. A failure stands out because it appears
  within minutes.
- **Region of interest.** Drag a rectangle over the bed and the model ignores everything else.
- **Test without printing.** One click runs the model on the current camera frame and shows what
  it found, so you can check the camera angle against a staged failure.
- **Dashboard.** Live camera with detection boxes, print progress, temperatures and a failure-risk
  meter for each printer. Light and dark mode.
- **Notifications.** Webhook and MQTT for Home Assistant, Node-RED, Discord and friends, plus an
  optional status message on the printer's display (M117).
- **Print history.** One record per physical print, even across pauses. Frames are kept during each
  print, and incidents can be marked as a real failure or a false alarm.
- **Offline.** No CDN, no external fonts. The model is downloaded once, on first start.

The web interface is in Czech.

## How it works

1. **Every 10 seconds**, while a printer reports `printing`, MoonEye takes a snapshot from its webcam.
2. **The model**, Obico's failure detector (YOLOv2, one class: *failure*), returns boxes around
   anything that looks like a failed print, each with a confidence.
3. **The decision.** The confidences in a frame are summed. A fast moving average of that sum is
   compared with two baselines: what this print has looked like so far, and what this printer's
   camera normally sees (about 20 hours of frames). A print is failing when the average rises
   clearly above both. The first 5 minutes of every print are never judged.
4. **The action.** A *warning* sends a notification once per print. A *failure* pauses the print
   (or only notifies, if the printer is set that way). After you resume, detection restarts its
   5-minute warm-up so the same evidence cannot pause the print again immediately.

The thresholds are Obico's own, fitted for this model and the 10-second cadence. MoonEye uses them
unchanged.

What it does **not** do: stringing, zits and other surface defects are too small to see in a
whole-bed webcam frame, and the model does not try.

## Getting started

### Run with podman

```bash
git clone https://github.com/martinkrob/mooneye.git
cd mooneye
podman-compose up -d
```

Open `http://<host>:8080`. On the first start MoonEye downloads the model (~200 MB) into
`./data/models` and verifies its checksum. The status dot in the header turns green when detection
is ready.

With Docker instead of podman, remove the `userns_mode` line from `compose.yml` and use
`docker compose up -d`.

### Add a printer

Open the menu ☰ and choose **Přidat tiskárnu** (*Add printer*):

1. **Připojení** (*Connection*): the Moonraker address (an IP or host name is enough; port 7125 is
   added automatically) and the webcam **snapshot** URL. With crowsnest that is usually
   `http://<printer>/webcam/?action=snapshot`. Both have a test button.
2. **Detekce** (*Detection*): what to do on a failure, the sensitivity, and optionally the region
   to watch, dragged over a live frame.
3. **Oznámení** (*Notifications*): webhook, MQTT and the printer display.

Then stage a failure (a tangle of filament on the bed) and use **Otestovat detekci teď** (*Test
detection now*) from the printer's ⋯ menu to see what the model makes of your camera.

### Configuration

Everything else is set in the UI. The environment only describes the deployment:

| Variable | Default | Purpose |
|---|---|---|
| `MOONEYE_PORT` | `8080` | HTTP port |
| `MOONEYE_DATA_PATH` | `./data` | Database, model and snapshots |
| `MOONEYE_DB_PATH` | `$DATA/mooneye` | H2 database file (without extension) |
| `MOONEYE_MODEL_PATH` | `$DATA/models/obico-failure-gen1.onnx` | The ONNX model |
| `MOONEYE_MODEL_URL` | Obico's URL | Where to download a missing model; empty = never download |
| `MOONEYE_MODEL_SHA256` | checksum of Obico's file | Expected checksum of the download |
| `MOONEYE_TELEMETRY_INTERVAL_MS` | `1000` | How often Moonraker is polled; `0` = only when needed |

On a network without internet access, copy the model into `./data/models` yourself and set
`MOONEYE_MODEL_URL=` (empty).

Memory: the JVM and the model need about 0.5–1 GB. On a desktop CPU one frame takes about 0.1 s.

### Security

MoonEye has no login, like Mainsail and Fluidd: it is meant for a home network. If it has to be
reachable from outside, put it behind a reverse proxy that adds authentication.

## Notifications

Webhook and MQTT receive JSON. A failure looks like this:

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

Events: `failure_warning`, `failure_detected` (action `PAUSED` or `NOTIFIED`), `telemetry` (every
10 s if enabled) and `test`.

## Trying the model on still images

No server and no printer needed. Save a few camera frames and run:

```bash
java -cp target/mooneye.jar h848.software.mooneye.tools.Evaluate \
     --model data/models/obico-failure-gen1.onnx --out annotated/ frames/
```

The tool prints the confidences for each image and writes copies with the boxes drawn in.

| Option | Effect |
|---|---|
| `--roi x,y,w,h` | Analyse only that part of each frame |
| `--sequence` | Replay the images as consecutive frames through the decision logic |

## REST API

The UI uses a plain JSON API that is also available to scripts:

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | Status, version, whether the model is loaded |
| `GET/PUT /api/settings` | History retention, snapshot interval |
| `GET/POST /api/printers`, `GET/PUT/DELETE /api/printers/{id}` | Printers (API key and MQTT password are write-only) |
| `GET /api/printers/{id}/status` | Telemetry, detection state and the current print |
| `GET /api/printers/{id}/frame` · `/camera` | Last analysed frame · fresh webcam frame (JPEG) |
| `POST /api/printers/{id}/analyse` | Run the model on a fresh frame without recording anything |
| `POST /api/printers/{id}/mute` | Silence alerts for the rest of the current print |
| `POST /api/printers/{id}/reset-baseline` | Forget the camera's normal view (after moving the camera) |
| `GET /api/printers/{id}/jobs`, `…/jobs/{jobId}/detections`, `…/jobs/{jobId}/snapshots` | Print history |
| `GET /api/printers/{id}/incidents`, `POST /api/incidents/{id}/review` | Warnings and failures, and their review |
| `GET /api/events` | Audit log |

## Development

Requirements: JDK 25 and Maven 3.9.

```bash
mvn package                                     # build and test -> target/mooneye.jar
mvn test -Dmooneye.model=/path/to/model.onnx    # also run the test against the real model
```

`DevServer` in `src/test` starts the application against three simulated printers (printing,
idle, offline) and a fake camera you can drop a tangle of filament into. You can work on the UI
without any hardware. Run it from the IDE and open `http://localhost:8090`.

Stack: Java 25, Javalin, H2 + JDBI, ONNX Runtime, Eclipse Paho. The front end is plain HTML, CSS
and JavaScript modules with no build step.

## Status

MoonEye is young. The detection pipeline is covered by tests against simulated printers and the
real model, but has not yet been proven on many real prints. Feedback from real printers is welcome.
Not done yet: the print-history view in the UI.

## Credits

The failure model and the decision algorithm come from
[Obico](https://github.com/TheSpaghettiDetective/obico-server) (formerly The Spaghetti Detective).
Obico's server is licensed under AGPL-3.0. The model weights are downloaded from obico.io at
runtime and are not part of this repository or the container image.
