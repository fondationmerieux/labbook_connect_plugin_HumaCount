# Installation — HumaCount 5D plugin for LabBook Connect

This folder contains everything needed to connect a **HUMAN HumaCount 5D** (5-part differential hematology analyzer) to LabBook through LabBook Connect.

| File | Role | Destination on the server |
|---|---|---|
| `AnalyzerHumaCount5D.jar` | Plugin (compiled Java) | `/storage/resource/connect/analyzer/plugin/` |
| `analyzer_humacount5d` | Settings file: analyzer identity and connection | `/storage/resource/connect/analyzer/setting/` |
| `mapping_humacount5d.toml` | Mapping between analyzer parameters and LabBook variables | `/storage/resource/connect/analyzer/mapping/` |

Connection: **HL7 v2.3.1** over TCP socket (MLLP). LabBook Connect listens (`server` mode); the analyzer connects to it.

The connection is **unidirectional**: the analyzer sends its results to LabBook, but LabBook does not send test requests to the analyzer.

---

## 1. Before you start

Collect the following information:

- The **IP address** of the HumaCount 5D on the laboratory network.
- A free **TCP port** on the LabBook server (e.g. `7501`), in the range 7500–7599 or 12300–12399. It must be different from the ports used by other analyzers.
- The **LabBook analysis and variable codes** of the complete blood count (CBC / NFS) that will receive the results.

---

## 2. Fill in the settings file

Open `analyzer_humacount5d` in a text editor and replace **every** `XXXX` / `X.X.X.X` value.

```toml
version = "1.0.1"

[analyzer]
brand = "HUMAN"
name = "HumaCount5D"
id = "HC5D_01"                       # Unique identifier — same value as in LabBook
plugin = "AnalyzerHumaCount5D"       # Do not change
url_lis = "http://localhost/sigl"    # LabBook address seen from LabBook Connect
operation_mode = "query"
archive_msg = "Y"
type_cnx = "socket"                  # Do not change
type_msg = "HL7"                     # Do not change
mapping = "/storage/resource/connect/analyzer/mapping/mapping_humacount5d.toml"

[analyzer.socket]
mode = "server"                      # LabBook Connect listens, the analyzer connects
ip = "192.168.1.70"                  # Analyzer IP address
port = 7501                          # Listening port (number, no quotes)
```

| Key | What to enter |
|---|---|
| `id` | Short unique identifier with no spaces, e.g. `HC5D_01`. You will enter **exactly the same value** in LabBook (step 4). |
| `url_lis` | Leave `http://localhost/sigl` when LabBook and LabBook Connect run on the same server. |
| `ip` | IP address of the analyzer |
| `port` | TCP port, written as a **number without quotes** |
| `mapping` | Path to the mapping file. Leave as is if you keep the default location. |

Do not modify `plugin`, `type_cnx` and `type_msg`.

> ⚠️ **Never leave a placeholder value (`XXXX`, `X.X.X.X`) in a file placed in the `setting/` folder.**
> LabBook reads every file in that folder. A single invalid file prevents **all** analyzers from loading, not only this one.

---

## 3. Copy the files to the server

```bash
cp AnalyzerHumaCount5D.jar     /storage/resource/connect/analyzer/plugin/
cp analyzer_humacount5d        /storage/resource/connect/analyzer/setting/
cp mapping_humacount5d.toml    /storage/resource/connect/analyzer/mapping/
```

Then **restart LabBook Connect**. The plugin (`.jar`) is only loaded at startup; restarting LabBook alone is not enough.

---

## 4. Declare the analyzer in LabBook

1. In LabBook, open the analyzer management page and add a new analyzer.
2. Enter the **same identifier** as `id` in the settings file (e.g. `HC5D_01`).
3. Set the **Mode** to **Query**.

The HumaCount 5D does not receive test requests. With **Batch**, no result is lost, but LabBook creates one useless transaction per sample in the **Transactions** page.

> The `operation_mode` key in the settings file has no effect on behavior. The **Mode of the analyzer record in LabBook** is the setting that counts.

---

## 5. Configure the HumaCount 5D

In the analyzer's LIS / communication settings:

| Setting | Value |
|---|---|
| Protocol | HL7 |
| Connection | TCP/IP (network) |
| LIS / host IP address | IP address of the LabBook server |
| LIS / host port | Same value as `port` in the settings file |
| Automatic transmission | Enabled |

Enter the **sample number (barcode)** on the analyzer exactly as it appears in LabBook, so that the result is attached to the right sample.

---

## 6. Adapt the mapping

`mapping_humacount5d.toml` links each analyzer parameter (WBC, RBC, HGB, PLT…) to a **LabBook variable code**.

The provided mapping targets the LabBook analysis **`B1030` — NFS - Hematologia** and its `NFS_*` variables. 28 parameters are mapped.

- **NEU#** is mapped to the variable whose code is `999` (label NEUT#).
- **HGB** is mapped to `NFS_HGB` only.
- **PDW** has no matching variable; its block is left commented at the end of the file.
- `NFS_MI#`, `NFS_MI%`, `NFS_GR#`, `NFS_GR%` (3-part differential) are not fed by this 5-part analyzer.

If your LabBook uses other analysis or variable codes, replace the `NFS_*` codes in the mapping with yours. If a code does not exist in LabBook, the result is still received but **no field is pre-filled**.

The mapping is reloaded without restarting LabBook Connect.

---

## 7. Check that it works

1. Look at the log:
   ```bash
   tail -f /var/log/labbook/connect/labbook_connect.log
   ```
   After the restart, the log must show `server socket open on port 7501` (or your port).
2. Run a sample on the analyzer.
3. On the analyzer, check that the transmission is reported as successful. The plugin acknowledges a result as accepted only when LabBook has accepted it: a failure on the analyzer means that LabBook refused the result (for instance an unknown sample number).
4. In LabBook, check that the message appears in **Transactions** and that the results are pre-filled in the analysis.

## Troubleshooting

| Symptom | Check |
|---|---|
| No analyzer loads at all | A file in `setting/` is invalid (placeholder value, syntax error). |
| LabBook answers "analyzer not added" | `plugin = "AnalyzerHumaCount5D"` spelled exactly, and LabBook Connect restarted after copying the `.jar`. |
| The analyzer shows "communication failed" | IP address and port on the analyzer, firewall, port not used by another analyzer. Check that the plugin version is **1.0.6** or later, and that the sample number exists in LabBook. |
| Results received but not pre-filled | Codes in the mapping do not match the LabBook variable codes. |
| Is the plugin really listening? | Only the log proves it. On a Podman host, `ss -ltnp` shows `conmon` on the port even when the plugin is not listening. |
