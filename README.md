# HumaCount 5D – LabBook Connect plugin

This plugin enables communication between a HUMAN HumaCount 5D hematology analyzer
(5-part differential) and LabBook.
The analyzer sends HL7 v2.3.1 results; the plugin forwards them to the LIS as HL7 v2.5.1.

## Installation note

This bundle is NOT a ready-to-use directory.

Files must be installed individually, either:
- by copying them manually to their corresponding locations on the server, or
- by uploading them through the LabBook user interface (when supported).

The files to install are in the `install/` folder, with a step-by-step guide:
[install/README.md](install/README.md).

The analyzer setting file is a sample and MUST be edited before use
(network parameters, analyzer ID, URLs).

Do not deploy the bundle as a single directory.

## Compatible models

| Models | Transactions |
|---|---|
| HumaCount 5D | results only |

Based on the HumaCount 5D LIS Interface Manual V1.

## Communication protocols

- Analyzer ↔ LabBook Connect: HL7 v2.3.1 over TCP socket, MLLP framing
- LabBook Connect ↔ LIS: HL7 v2.5.1 (HTTP)

## Supported transactions

- LAB-29 (Results)  
  HL7 ORU^R01 (from analyzer) → HL7 OUL^R22 (to LIS)  
  HL7 ACK (to analyzer): `AA` when LabBook accepted the result, `AE` otherwise

The communication is unidirectional. The analyzer does not accept orders from the host:
LAB-27 and LAB-28 are not implemented (`lab28()` returns `null`).
Set the **Mode** of the analyzer record in LabBook to **Query**. With Batch, no result is lost,
but LabBook creates one unused LAB-28 transaction per sample.

## Deployment modes

- server (validated, production mode)  
  LabBook Connect listens on a TCP port and waits for the analyzer connection.

## Configuration files

Two configuration files are required for each HumaCount 5D analyzer instance:
- one analyzer setting file (connection and routing)
- one mapping file (tests and result mapping)

### 1) Analyzer settings

Location:  
    /storage/resource/connect/analyzer/setting/

Sample file:  
    doc/analyzer_humacount5d.toml

Important:
- The operator MUST edit this file before use.
- The `operation_mode` key is informative only; the Mode of the LabBook analyzer record is the
  setting that counts.
- Allowed TCP port ranges:
  - 7500–7599
  - 12300–12399
- LabBook reads every file in the setting folder. A single invalid file (placeholder value,
  syntax error) prevents all analyzers from loading.

### 2) Mapping file

Location:  
    /storage/resource/connect/analyzer/mapping/

Sample file:  
    doc/mapping_humacount5d.toml

Notes:
- `vendor_result_code` is OBX-3.1 sent by the analyzer (LOINC or 99MRC code).
- `lis_result_code` is the LabBook variable code (`code_var`).
- The analyzer sends `OBR-4.1 = 01001` (Automated Count).
- The sample maps 28 parameters onto the LabBook analysis `B1030` "NFS - Hematologia"
  (`NFS_*` variables). Adapt the codes to the LabBook referential of the site.
- PDW has no matching variable in that analysis; its block is left commented at the end of the
  file.
- A result whose code is not mapped is still received and stored, but no field is pre-filled.

## Logging

- Logs use the global LabBook Connect logging configuration.
- After startup the log shows `server socket open on port <port>`. On a Podman host,
  `ss -ltnp` shows `conmon` on the port even when the plugin is not listening; only the log
  proves it.

## Message archiving

Message archiving is controlled by the `archive_msg` setting in the analyzer configuration file.

When enabled (`archive_msg = "Y"`), raw messages are archived on disk for traceability and diagnostics.

Archived messages are stored per analyzer instance in:
    /storage/resource/connect/analyzer/{id_analyzer}/

## Testing without an instrument

`script/simulate_humacount5d_send_result.py` plays the part of the instrument. It connects to
the plugin the way the analyzer does, sends one ORU^R01 complete blood count framed in MLLP and
prints the ACK returned by the plugin.

```bash
python3 script/simulate_humacount5d_send_result.py <connect host> <analyzer port>
```

The port is the one set in the analyzer settings file. The patient and sample data in the
message are fictitious. `MSA|AA` means that LabBook accepted the result; `MSA|AE` means that it
was refused or could not be delivered (for instance an unknown sample number).

## Updating the plugin

- Mapping: reloaded without restarting LabBook Connect.
- JAR: LabBook Connect MUST be restarted. Restarting LabBook alone is not enough.

## Versioning

- Plugin version is embedded in the JAR (logged by `info()`).
- Setting and mapping files have independent versions.

## Plugin API

The plugin implements the interface defined in `src/plugin/Analyzer.java` of
[LabBook Connect](https://github.com/fondationmerieux/labbook_connect) and communicates with it
through `src/plugin/Connect_util.java`.

## License

This project is distributed under the GNU General Public License v2.0, see [LICENSE.md](LICENSE.md).
