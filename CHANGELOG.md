# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.6] - 2026-09-23
### Fixed
- The ACK sent back to the analyzer did not depend on the LabBook answer. `buildAck()` always
  returned `MSA|AA`, so a message refused by the LIS, or never delivered (LIS stopped, network
  down, unknown sample), was still acknowledged as accepted. The analyzer considered the result
  as sent and the laboratory had no sign of the loss.
- The LIS answer is now read (`extractMsaCode`, `lisAccepted`). The ACK carries `AA` only when
  LabBook accepted the message, `AE` otherwise. Three failure cases are covered: no answer, the
  `ERROR send_hl7_msg` string returned by `Connect_util`, and a negative ACK (`AE`, `AR`, `CE`,
  `CR`).
- The MSA segment keeps the same layout (`MSA|<code>|<MSH-10>`, two fields), no optional field
  is added.
- A `WARN` line with the LIS answer is logged for every refusal.

### Changed
- `buildAck(String receivedMsgId)` becomes `buildAck(String receivedMsgId, String ackCode)`.

## [1.0.5] - 2026-09-11
### Fixed
- Infinite loop at end of stream. `Connect_util.readMLLPMessage()` returns `""` (never `null`)
  when the analyzer disconnects. The empty-message branch used `continue`, so the thread looped
  at 100 % CPU and never went back to `accept()`: only the first session after each start was
  accepted. The branch now uses `break`, the session is closed and the server waits for the next
  connection.

## [1.0.4] - 2026-09-11
### Fixed
- Server socket rebind: `setReuseAddress(true)` is now set before the bind. Set after it, it had
  no effect and the rebind failed with "Address already in use" after `stopListening()`.
- A bind failure is reported to the `listenDevice()` loop so that the retry delay applies, and
  `serverSocket` is set back to `null` in `stopListening()`.

## [1.0.3] - 2026-09
### Fixed
- The message control ID was read from MSH-11 instead of MSH-10, so the ACK carried
  `MSA|AA|P` and the analyzer reported a failure.

## [1.0.2] - 2026-09
### Added
- Conversion of the `ORU^R01` sent by the analyzer into the `OUL^R22` expected by LabBook for
  LAB-29. Without it the LIS answered `AE` "Unexpected message type".

## [1.0.1] - 2026-09
### Fixed
- `test()` returned a description instead of the class name, so LabBook answered
  404 "analyzer not added".

## [1.0.0] - 2026-09-07
### Added
- Initial release of the AnalyzerHumaCount5D plugin for LabBook Connect
- Unidirectional HL7 v2.3.1 over TCP/IP with MLLP framing, server mode
- OBX mapping from the TOML mapping file
- Non-numeric OBX (IS/ED metadata) skipped
- ACK returned to the analyzer after each ORU^R01
