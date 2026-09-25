# LabBook Connect plugin — HUMAN HumaCount 5D

## Project

This repository contains the **AnalyzerHumaCount5D** plugin for [LabBook Connect](https://github.com/fondationmerieux/labbook_connect).
It receives the results sent by the **HUMAN HumaCount 5D** 5-part differential hematology analyzer and forwards them to [LabBook](https://www.lab-book.org/en/) as IHE-LAW LAB-29 transactions.

| Item | Value |
|---|---|
| Analyzer | HUMAN HumaCount 5D, 5-part differential hematology |
| Protocol | HL7 v2.3.1 over MLLP / TCP |
| Direction | **Unidirectional** — the analyzer pushes results; no work order is sent to the analyzer (`lab28()` returns `null`) |
| Connection | LabBook Connect listens (`socket` / `server`), the analyzer connects as a client |
| Default port | `7501` |
| Plugin class | `AnalyzerHumaCount5D` |
| Current version | JAR **1.0.5**, mapping **v4.0** |

## Contents

| File | Role |
|---|---|
| `AnalyzerHumaCount5D.jar` | Compiled plugin |
| Settings file (`.toml`) | Analyzer declaration for LabBook Connect (id, plugin, connection, port) |
| `mapping_humacount5d.toml` | Mapping between analyzer parameters and LabBook variable codes |

## Requirements

- A running LabBook and LabBook Connect installation (Linux, Podman).
- Development: Java and Eclipse (Eclipse 4.29.0 with OpenJDK 21 were used).

## Installation

1. Copy `AnalyzerHumaCount5D.jar` into the LabBook Connect plugin directory:
   `/storage/resource/connect/analyzer/plugin/`
2. Copy the settings file into:
   `/storage/resource/connect/analyzer/setting/`
   Check every value before copying — see the warning below.
3. Copy `mapping_humacount5d.toml` into the directory referenced by the settings file.
4. **Restart LabBook Connect.** A new or updated JAR is only loaded at startup; restarting LabBook alone is not enough.
5. In LabBook, create the analyzer record with the same identifier as in the settings file (`HC5D_01`) and set its **Mode** to **Query** (see below).
6. On the HumaCount 5D, configure the LIS host with the IP address of the LabBook Connect server and port `7501`, protocol HL7.

> **Warning.** LabBook reads every file in `/storage/resource/connect/analyzer/setting/`. A single invalid file (for instance `port = TO_COMPLETE`) prevents **all** analyzers from loading. Never leave an incomplete settings file in that directory.

## Configuration

| Key | Value |
|---|---|
| `analyzer.id` | `HC5D_01` |
| `analyzer.plugin` | `AnalyzerHumaCount5D` |
| Connection type / mode | `socket` / `server` |
| Port | `7501` |

`analyzer.operation_mode` in the settings file is read and logged but does not change the plugin's behavior.

### Analyzer mode in LabBook: Query

The **Mode (Batch / Query)** of the analyzer record in LabBook controls whether LabBook sends a work order (LAB-28, `OML^O33`) to the analyzer each time a sample is created. The HumaCount 5D is unidirectional, so the mode must be **Query**.
With Batch, no result is lost, but one unused LAB-28 transaction is created per sample in the Transactions page.

## Mapping

`mapping_humacount5d.toml` v4.0 targets the existing LabBook analysis **`B1030` — NFS - Hematologia** and its `NFS_*` variables. 28 parameters are mapped.

The analyzer sends `OBR-4.1 = 01001` (Automated Count).

Notes:

- **NEU#** is mapped to the variable whose code is literally `999` (label NEUT#).
- **HGB** is mapped to `NFS_HGB` only (`NFS_HGB_1` is not fed).
- **PDW** (LOINC `32207-3`) has no matching variable; its block is left commented at the end of the file.
- `NFS_MI#`, `NFS_MI%`, `NFS_GR#`, `NFS_GR%` (3-part differential variables) are not fed by this 5-part analyzer.

If your LabBook uses other analysis or variable codes, adapt the `NFS_*` codes in the mapping. The mapping is reloaded without restarting LabBook Connect.

## Technical behavior

- The analyzer sends `ORU^R01`; LabBook LAB-29 only accepts `OUL^R22`. The plugin converts the message before forwarding it.
- The plugin acknowledges each message with an HL7 ACK (`MSA|AA|<MSH-10>`).
- After the analyzer disconnects, the plugin goes back to waiting for the next connection.

## Troubleshooting

- Log file: `/var/log/labbook/connect/labbook_connect.log`
- To confirm that the plugin is listening, look for `server socket open on port 7501` in the log. On a Podman host, `ss -ltnp | grep 7501` shows `conmon` even when the plugin is not listening, so it is not proof.
- Results reach `analyzer_result` but are not pre-filled: check that the codes in the mapping exist as variable codes in the LabBook analysis.
- LabBook answers **404 "analyzer not added"**: check that the plugin name in the settings file is exactly `AnalyzerHumaCount5D` and that LabBook Connect was restarted after the JAR was copied.

## Changelog

| Version | Change |
|---|---|
| 1.0.1 | `test()` returns the class name, so LabBook can register the analyzer (fixes 404 "analyzer not added") |
| 1.0.2 | Conversion of `ORU^R01` to `OUL^R22` for LAB-29 |
| 1.0.3 | ACK uses MSH-10 (message control ID) instead of MSH-11 |
| 1.0.4 | Server socket: `setReuseAddress` set before bind; bind failures reported; socket released on stop |
| 1.0.5 | Fixed an infinite loop on end of stream: after the first disconnection, the listener stopped accepting new sessions and used 100 % CPU |

## Plugin API

The plugin implements the interface defined in `src/plugin/Analyzer.java` and communicates with LabBook Connect through `src/plugin/Connect_util.java`. See the [AnalyzerDemo plugin](https://github.com/fondationmerieux/labbook_connect_plugin_demo) for reference.

## License

This project is distributed under the [GNU General Public License v2.0](https://github.com/fondationmerieux/labbook_connect/blob/master/LICENSE.md).
