package plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moandjiezana.toml.Toml;

/**
 * HumaCount 5D analyzer plugin for LabBook Connect.
 *
 * Implements unidirectional HL7 v2.3.1 communication over TCP/IP with MLLP framing.
 *
 * The HumaCount 5D acts as TCP client: it connects to the LIS (LabBook Connect)
 * and sends ORU^R01 messages containing hematology results.
 * This plugin listens in server mode, accepts the connection, receives ORU^R01
 * messages and forwards them to LabBook (LAB-29) after OBX mapping.
 *
 * Supported flow:
 *   HumaCount 5D ──ORU^R01/MLLP──► LabBook Connect ──LAB-29──► LabBook LIS
 *
 * Protocol references:
 *   - HumaCount 5D LIS Interface Manual V1 (2017-04-04, Mathias Kamprath)
 *   - HL7 v2.3.1 standard, MLLP framing
 *
 * MLLP framing:
 *   <SB=0x0B> + HL7 message (UTF-8) + <EB=0x1C> + <CR=0x0D>
 *
 * OBX-3 format from analyzer: ID^Name^EncodingSystem (e.g. 6690-2^WBC^LN)
 * Mapping: vendor_result_code in TOML matches OBX-3.1 (the ID component).
 *
 * Changelog:
 *   1.0.0 - 2026-09-07 - Initial release (NFS de base: WBC, RBC, HGB, HCT, PLT)
 *   1.0.4 - 2026-09-11 - Correction du rebind du socket serveur :
 *                        setReuseAddress(true) est desormais pose AVANT le bind
 *                        (place apres, il etait sans effet et le rebind echouait
 *                        en "Address already in use" apres stopListening()).
 *                        L'echec de bind remonte a la boucle de listenDevice()
 *                        pour que le backoff s'applique, et serverSocket est
 *                        remis a null dans stopListening().
 *                        Aucun changement de parsing, de mapping ni d'ACK.
 *   1.0.5 - 2026-09-11 - CORRECTION MAJEURE : boucle infinie sur fin de flux.
 *                        Connect_util.readMLLPMessage() renvoie "" (jamais null)
 *                        quand l'automate se deconnecte (read() == -1). Le test
 *                        isEmpty() faisait 'continue' -> boucle serree a 100% de
 *                        CPU, le thread ne revenait jamais a accept(), et plus
 *                        aucune connexion suivante n'etait acceptee.
 *                        Desormais 'break' : la session est fermee proprement et
 *                        le serveur se remet en attente.
 *   1.0.6 - 2026-09-23 - CORRECTION : l'acquittement renvoye a l'automate ne
 *                        dependait pas de la reponse de LabBook. buildAck()
 *                        produisait toujours "MSA|AA", si bien qu'un message
 *                        refuse par le LIS, ou non transmis du tout (LIS arrete,
 *                        reseau coupe, echantillon inconnu), etait tout de meme
 *                        acquitte positivement : l'automate considerait le
 *                        resultat comme transmis et le laboratoire n'avait aucun
 *                        signal de la perte.
 *                        Desormais la reponse de LabBook est analysee
 *                        (lisAccepted / extractMsaCode) et l'ACK renvoye a
 *                        l'automate porte AA uniquement si le LIS a accepte,
 *                        AE sinon. La forme du segment est inchangee
 *                        (MSA|<code>|<MSH-10>, deux champs), pour ne pas
 *                        modifier ce que l'automate sait deja lire.
 */
public class AnalyzerHumaCount5D implements Analyzer {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzerHumaCount5D.class);

    private final String jar_version = "1.0.6";

    private Thread listenerThread;

    // ── Analyzer interface fields ──────────────────────────────────────────────
    protected String version        = "";
    protected String id_analyzer    = "";
    protected String url_upstream_lab27 = "";
    protected String url_upstream_lab29 = "";
    protected int    port_analyzer  = 0;
    protected String type_cnx       = "";
    protected String type_msg       = "";
    protected String archive_msg    = "";
    protected String operation_mode = "query";
    protected String mode           = "";
    protected String ip_analyzer    = "";
    protected String mappingPath    = "";

    private Toml   mappingToml = null;
    private Socket socket      = null;
    private InputStream  inputStream  = null;
    private OutputStream outputStream = null;
    private ServerSocket serverSocket = null;

    private final AtomicBoolean listening = new AtomicBoolean(false);

    // MLLP delimiters
    private static final byte SB = 0x0B;  // Start Block
    private static final byte EB = 0x1C;  // End Block
    private static final byte CR = 0x0D;  // Carriage Return

    // ── Getters / Setters ──────────────────────────────────────────────────────

    @Override public String getId_analyzer()            { return id_analyzer; }
    @Override public void   setId_analyzer(String v)   { this.id_analyzer = v; }
    @Override public String getUrl_upstream_lab27()     { return url_upstream_lab27; }
    @Override public void   setUrl_upstream_lab27(String url) { this.url_upstream_lab27 = url; }
    @Override public String getUrl_upstream_lab29()     { return url_upstream_lab29; }
    @Override public void   setUrl_upstream_lab29(String url) { this.url_upstream_lab29 = url; }
    @Override public void   setVersion(String v)        { this.version = v; }
    @Override public void   setType_cnx(String v)       { this.type_cnx = v; }
    @Override public void   setType_msg(String v)       { this.type_msg = v; }
    @Override public void   setArchive_msg(String v)    { this.archive_msg = v; }
    @Override public void   setOperationMode(String v)  { this.operation_mode = v; }
    @Override public void   setMode(String v)           { this.mode = v; }
    @Override public void   setIp_analyzer(String v)    { this.ip_analyzer = v; }
    @Override public void   setPort_analyzer(int v)     { this.port_analyzer = v; }
    @Override public String getMappingPath()             { return mappingPath; }
    @Override public void   setMappingPath(String v)    { this.mappingPath = v; }

    @Override
    public String test() {
        // Identite du plugin : cette valeur est comparee telle quelle au champ
        // analyzer.plugin du fichier de reglage par AnalyzerLoader.parse_setting().
        // Elle doit donc etre le seul nom de la classe, sans version ni commentaire,
        // sans quoi aucune instance n'est creee et le LIS repond 404.
        return this.getClass().getSimpleName();
    }

    @Override
    public String info() {
        return String.format(
            "AnalyzerHumaCount5D [jar=%s, version=%s, id=%s, lab29=%s, type_cnx=%s, mode=%s, port=%d]",
            jar_version, version, id_analyzer, url_upstream_lab29, type_cnx, mode, port_analyzer
        );
    }

    @Override
    public boolean isListening() {
        return listening.get();
    }

    // ── LAB flows ─────────────────────────────────────────────────────────────

    @Override
    public String lab27(final String msg) {
        // Not used (unidirectional: HumaCount → LabBook only)
        logger.info("lab27 called but not supported for HumaCount 5D (unidirectional mode)");
        return null;
    }

    @Override
    public String lab28(final String msg) {
        // Not used (unidirectional: no order sending to analyzer)
        logger.info("lab28 called but not supported for HumaCount 5D (unidirectional mode)");
        return null;
    }

    /**
     * Receives an ORU^R01 message from the HumaCount 5D, applies OBX mapping,
     * and forwards it to LabBook LIS via LAB-29.
     *
     * @param msg HL7 ORU^R01 message (ER7 format, without MLLP framing)
     * @return HL7 ACK response from LabBook, or null on error
     */
    @Override
    public String lab29(final String msg) {
        String mapped = applyMappingORU_R01(msg);
        logger.info("LAB-29 HumaCount5D MAPPED: {}", mapped.replace("\r", "<CR>\n"));

        // LabBook n'accepte en LAB-29 que des messages OUL^R22 et cherche l'identifiant
        // d'echantillon dans SPM-2. L'automate, lui, emet un ORU^R01 v2.3.1 sans SPM.
        // Sans cette conversion le LIS repond AE "Unexpected message type (ORU^R01)".
        String oul = convertORU_R01toOUL_R22(mapped);
        logger.info("LAB-29 HumaCount5D OUL^R22: {}", oul.replace("\r", "<CR>\n"));

        return processLabTransaction(oul, "LAB-29", this.url_upstream_lab29);
    }

    /**
     * Converts the analyzer ORU^R01 (HL7 v2.3.1) into the OUL^R22 (v2.5.1) that LabBook expects.
     *
     * Only the header segments are rebuilt; the OBX segments are carried over unchanged,
     * mapping already applied. They are structurally compatible: OBX-11 already sits at
     * field index 11, which is where the LIS reads the result status.
     *
     * Segment mapping:
     *   OBR-3   -> SPM-2, ORC-2, OBR-2   (specimen identifier, "956" on this instrument)
     *   OBR-4.1 -> OBR-4                 (test code, resolved through [[ivd_test]] if declared)
     *   PID-3   -> PID-3                 (patient identifier, often empty on this instrument)
     *   PID-5   -> PID-5                 (patient name)
     *   MSH-10  -> MSH-10                (control id kept, so the LIS acknowledgement correlates)
     *   PV1                              dropped, not part of OUL^R22
     *
     * @param hl7Message mapped ORU^R01 message
     * @return OUL^R22 message, or the input unchanged if it cannot be read
     */
    private String convertORU_R01toOUL_R22(String hl7Message) {
        try {
            if (hl7Message == null || hl7Message.isEmpty()) {
                return hl7Message;
            }

            String[] segments = hl7Message.replace("\r\n", "\r").replace("\n", "\r").split("\r");

            String specimenId = "";
            String vendorTestCode = "";
            String patientId = "";
            String patientName = "";
            String controlId = "";
            List<String> obxSegments = new ArrayList<>();

            for (String seg : segments) {
                if (seg == null || seg.trim().isEmpty()) {
                    continue;
                }
                String[] f = seg.split("\\|", -1);

                if (seg.startsWith("MSH|") && f.length > 9) {
                    controlId = f[9].trim();

                } else if (seg.startsWith("PID|")) {
                    if (f.length > 3) patientId   = f[3].trim();
                    if (f.length > 5) patientName = f[5].trim();

                } else if (seg.startsWith("OBR|")) {
                    if (f.length > 3) specimenId = f[3].trim();
                    if (f.length > 4) {
                        String[] c = f[4].split("\\^", -1);
                        vendorTestCode = (c.length > 0) ? c[0].trim() : "";
                    }

                } else if (seg.startsWith("OBX|")) {
                    obxSegments.add(seg);
                }
            }

            if (specimenId.isEmpty()) {
                logger.warn("convertORU_R01toOUL_R22: no specimen identifier in OBR-3, "
                        + "the LIS will not be able to attach the results");
            }

            // Code d'analyse cote LIS, via [[ivd_test]] si le fichier de correspondances en declare un
            String lisTestCode = vendorTestCode;
            if (mappingToml != null) {
                List<Toml> tests = mappingToml.getTables("ivd_test");
                if (tests != null && !vendorTestCode.isEmpty()) {
                    for (Toml t : tests) {
                        String v = t.getString("vendor_test_code");
                        if (v != null && v.trim().equals(vendorTestCode)) {
                            String lis = t.getString("lis_test_code");
                            if (lis != null && !lis.trim().isEmpty()) {
                                lisTestCode = lis.trim();
                            }
                            break;
                        }
                    }
                }
            }

            if (controlId.isEmpty()) {
                controlId = "HC5D" + System.currentTimeMillis();
            }

            String datetime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

            StringBuilder out = new StringBuilder();
            out.append("MSH|^~\\&|HumaCount5D|Analyzer|LabBook|LIS|").append(datetime)
               .append("||OUL^R22|").append(controlId).append("|P|2.5.1\r");
            out.append("PID|||").append(patientId).append("||").append(patientName).append("\r");
            out.append("SPM|1|").append(specimenId).append("\r");
            out.append("ORC|RE|").append(specimenId).append("\r");
            out.append("OBR|1|").append(specimenId).append("||^^^").append(lisTestCode).append("\r");
            for (String obx : obxSegments) {
                out.append(obx).append("\r");
            }

            logger.info("convertORU_R01toOUL_R22: specimen={} test={} -> {} obx, controlId={}",
                    specimenId, lisTestCode, obxSegments.size(), controlId);

            return out.toString();

        } catch (Exception e) {
            logger.error("ERROR convertORU_R01toOUL_R22: {}", e.getMessage(), e);
            return hl7Message;
        }
    }

    /**
     * Forwards a mapped HL7 payload to LabBook LIS and returns the response.
     *
     * @param msg     HL7 payload (ER7, without MLLP framing)
     * @param labType LAB-27 / LAB-29 (for logging)
     * @param url     LabBook LIS endpoint URL
     * @return HL7 response from LIS, or null on error
     */
    private String processLabTransaction(String msg, String labType, String url) {
        logger.info("{} HumaCount5D: Received message\n{}", labType, msg);
        try {
            Connect_util.archiveMessage(this.getId_analyzer(), this.archive_msg, msg, labType, "Analyzer");
            String response = Connect_util.send_hl7_msg(this, url, msg);
            logger.info("{} HumaCount5D: LIS response: {}", labType,
                response == null ? "null" : response.replace("\r", "<CR>\n"));
            return response;
        } catch (Exception e) {
            logger.error("ERROR processLabTransaction: {}", e.getMessage());
            return null;
        }
    }

    // ── OBX Mapping ───────────────────────────────────────────────────────────

    /**
     * Applies result mapping (HumaCount 5D → LabBook) on an incoming ORU^R01 message.
     *
     * Behavior:
     *  - Parses OBR-4 to identify the test type (e.g. "00001" = Automated Count).
     *  - Iterates OBX segments; for each, extracts OBX-3.1 as the vendor result code.
     *  - Skips non-numeric OBX (OBX-2 != "NM") and metadata OBX (mode, remark, etc.).
     *  - Matches vendor_result_code in TOML [ivd_mapping] and replaces OBX-3
     *    with lis_result_code.
     *  - Optionally replaces OBX-6 (unit) with lis_unit if defined in TOML.
     *
     * OBX-11 is natively present in HumaCount 5D HL7 messages (position f[11] = "F").
     * String.join("|", f) preserves it intact — no manual pipe insertion needed.
     *
     * @param hl7Message HL7 ORU^R01 message (ER7 format)
     * @return Mapped HL7 message ready for LabBook LIS
     */
    private String applyMappingORU_R01(String hl7Message) {
        try {
            // Normalize line endings
            hl7Message = hl7Message.replace("\r\n", "\r").replace("\n", "\r");
            String[] segments = hl7Message.split("\\r?\\n|\\r", -1);

            // Extract test type from OBR-4 (first component)
            // e.g. OBR-4 = "00001^Automated Count^99MRC" → currentTest = "00001"
            String currentTest = "";
            for (String seg : segments) {
                if (seg.startsWith("OBR|")) {
                    String[] f = seg.split("\\|", -1);
                    if (f.length > 4) {
                        String[] comps = f[4].split("\\^", -1);
                        if (comps.length > 0) {
                            currentTest = comps[0].trim();
                        }
                    }
                    break;
                }
            }
            logger.info("HumaCount5D applyMapping: currentTest={}", currentTest);

            if (mappingToml == null) {
                logger.warn("HumaCount5D: mappingToml is null, returning original message");
                return hl7Message;
            }

            List<Toml> maps = mappingToml.getTables("ivd_mapping");
            if (maps == null || maps.isEmpty()) {
                logger.warn("HumaCount5D: no [ivd_mapping] entries in TOML");
                return hl7Message;
            }

            // Process each OBX segment
            for (int i = 0; i < segments.length; i++) {
                if (!segments[i].startsWith("OBX|")) continue;

                String[] f = segments[i].split("\\|", -1);

                // Minimum: OBX|set_id|value_type|obx3|obx4|obx5|obx6|...
                if (f.length < 7) continue;

                // OBX-2 : only map numeric results (NM)
                // Metadata OBX (IS type: loading mode, blood mode, test mode, etc.) are skipped
                String obx2 = f[2].trim();
                if (!"NM".equalsIgnoreCase(obx2)) {
                    logger.info("HumaCount5D: skipping non-NM OBX (OBX-2={}) segment: {}", obx2, segments[i]);
                    continue;
                }

                // OBX-3 : extract vendor result code (first component = ID)
                // Format: ID^Name^EncodingSystem (e.g. "6690-2^WBC^LN")
                String[] comps = f[3].split("\\^", -1);
                String vendorCode = comps[0].trim();

                logger.info("HumaCount5D: OBX vendorCode={} test={}", vendorCode, currentTest);

                // Match in TOML
                for (Toml m : maps) {
                    String test   = m.getString("test");
                    String vendor = m.getString("vendor_result_code");

                    if (test == null || vendor == null) continue;
                    if (!currentTest.equals(test)) continue;

                    // vendor_result_code in TOML must match OBX-3.1
                    // Trailing ^ is NOT expected here (OBX-3 component, not ASTM field)
                    if (!vendorCode.equalsIgnoreCase(vendor.trim())) continue;

                    // Match found — replace OBX-3 with lis_result_code
                    String lisCode = m.getString("lis_result_code");
                    if (lisCode != null && !lisCode.isEmpty()) {
                        f[3] = lisCode;
                        logger.info("HumaCount5D: mapped vendorCode={} → lisCode={}", vendorCode, lisCode);
                    }

                    // Optionally replace OBX-6 (unit) with lis_unit
                    // lis_unit="" → clears OBX-6; lis_unit absent (null) → keeps original
                    String lisUnit = m.getString("lis_unit");
                    if (lisUnit != null && f.length > 6) {
                        f[6] = lisUnit;
                    }

                    // Rebuild segment preserving all fields (including OBX-11 = "F" at f[11])
                    segments[i] = String.join("|", f);
                    break;
                }
            }

            return String.join("\r", segments);

        } catch (Exception e) {
            logger.error("ERROR applyMappingORU_R01: {}", e.getMessage());
            return hl7Message;
        }
    }

    // ── ACK builder ───────────────────────────────────────────────────────────

    /**
     * Builds a minimal ACK^R01 response for the HumaCount 5D.
     *
     * The HumaCount 5D expects an ACK after each ORU^R01.
     * Format per manual: MSH + MSA (AA = accepted, AE = application error).
     *
     * 1.0.6 : the acknowledgement code is now a parameter instead of a constant.
     * The segment keeps exactly two fields (MSA-1 and MSA-2) : the analyzer
     * proved sensitive to the content of MSA-2 (see extractMsgControlId), so no
     * optional field is appended.
     *
     * @param receivedMsgId MSH-10 of the received message
     * @param ackCode       "AA" when LabBook accepted the result, "AE" otherwise
     * @return HL7 ACK message string (without MLLP framing)
     */
    private String buildAck(String receivedMsgId, String ackCode) {
        String now = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
        String code = ("AA".equals(ackCode) || "AE".equals(ackCode)) ? ackCode : "AE";
        // HL7 v2.3.1 ACK as expected by HumaCount 5D
        return "MSH|^~\\&|LabBookConnect|LIS|||" + now + "||ACK^R01|ACK-" + now + "|P|2.3.1||||||UNICODE\r"
             + "MSA|" + code + "|" + receivedMsgId + "\r";
    }

    /**
     * Extracts MSA-1 (acknowledgement code) from a LabBook response.
     *
     * @param hl7Response raw response returned by Connect_util.send_hl7_msg
     * @return the acknowledgement code in upper case ("AA", "AE", "AR", "CA"...),
     *         or "" when the response carries no readable MSA segment
     */
    private String extractMsaCode(String hl7Response) {
        if (hl7Response == null) return "";
        try {
            for (String seg : hl7Response.split("[\\r\\n]+")) {
                if (seg.startsWith("MSA|")) {
                    String[] f = seg.split("\\|", -1);
                    return (f.length > 1) ? f[1].trim().toUpperCase() : "";
                }
            }
        } catch (Exception ignore) {}
        return "";
    }

    /**
     * Tells whether LabBook actually took the result.
     *
     * Three failure shapes have to be caught, and only the last one is an ACK :
     *   - null : processAnalyzerMsg() rejected the message before any transmission
     *     (not an ORU^R01, missing MSH), or processLabTransaction() caught an
     *     exception;
     *   - a string starting with "ERROR send_hl7_msg" : Connect_util reports a
     *     transport failure this way. It never returns null on failure, so a
     *     plain null test would silently let this case through;
     *   - a real ACK carrying MSA-1 = AE / AR / CE / CR : LabBook was reached but
     *     refused the message (unknown specimen, mapping error, closed file).
     *
     * @param lisResponse raw response returned by processAnalyzerMsg
     * @return true only when LabBook answered with a positive acknowledgement
     */
    private boolean lisAccepted(String lisResponse) {
        if (lisResponse == null || lisResponse.trim().isEmpty()) return false;
        if (lisResponse.startsWith("ERROR send_hl7_msg")) return false;
        String code = extractMsaCode(lisResponse);
        return "AA".equals(code) || "CA".equals(code);
    }

    /**
     * Extracts MSH-10 (message control ID) from a raw HL7 message.
     */
    private String extractMsgControlId(String hl7Message) {
        try {
            for (String seg : hl7Message.split("[\\r\\n]+")) {
                if (seg.startsWith("MSH|")) {
                    // MSH decoupe par "|" : f[0]=MSH f[1]=^~\\& f[2]=MSH-3 ... f[8]=MSH-9 f[9]=MSH-10
                    // MSH-10 est l'identifiant de controle, a l'indice 9. L'indice 10 est MSH-11,
                    // l'identifiant de traitement ("P"), que l'automate ne reconnait pas dans MSA-2
                    // et qui lui fait conclure a un echec de communication.
                    String[] f = seg.split("\\|", -1);
                    return (f.length > 9) ? f[9].trim() : "";
                }
            }
        } catch (Exception ignore) {}
        return "";
    }

    // ── Socket listener ───────────────────────────────────────────────────────

    /**
     * Main listener loop. Supports server mode only (HumaCount 5D connects as client).
     * type_cnx must be "socket", mode must be "server".
     */
    @Override
    public void listenDevice() {
        this.mappingToml = Connect_util.loadMappingToml(this.getMappingPath());

        if (this.listening.get()) {
            logger.info("listenDevice() already running, ignoring call");
            return;
        }

        logger.info("HumaCount5D listenDevice: type_cnx={} mode={} port={}",
            type_cnx, mode, port_analyzer);

        if (!"socket".equalsIgnoreCase(this.type_cnx)) {
            logger.info("Unsupported connection type: {}", type_cnx);
            this.listening.set(false);
            return;
        }

        if (!"server".equalsIgnoreCase(this.mode)) {
            logger.info("HumaCount 5D requires mode=server (analyzer connects as client). mode={}", mode);
            this.listening.set(false);
            return;
        }

        this.listening.set(true);

        this.listenerThread = new Thread(() -> {
            int backoffTime    = 5000;
            int maxBackoffTime = 60000;

            while (this.listening.get()) {
                try {
                    logger.info("HumaCount5D: Starting HL7 server on port {}", port_analyzer);
                    startHL7Server();
                    backoffTime = 5000; // reset after successful session
                } catch (Exception e) {
                    logger.error("ERROR in listenDevice loop: {}", e.getMessage());
                    if (!this.listening.get()) break;
                    try {
                        logger.info("HumaCount5D: Waiting {}ms before retry...", backoffTime);
                        Thread.sleep(backoffTime);
                        backoffTime = Math.min(backoffTime * 2, maxBackoffTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.info("HumaCount5D: listenDevice() thread stopped");
        }, "AnalyzerHumaCount5D-" + (this.id_analyzer == null ? "UNKNOWN" : this.id_analyzer));

        this.listenerThread.start();
    }

    /**
     * Opens a TCP server socket and handles one client connection at a time.
     * The HumaCount 5D connects to this server and sends ORU^R01 messages.
     */
    private void startHL7Server() throws IOException {
        // Le bind est volontairement HORS du try : un echec de bind doit remonter
        // a la boucle de listenDevice() pour declencher le backoff.
        if (serverSocket == null || serverSocket.isClosed()) {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);                              // AVANT le bind
            ss.bind(new InetSocketAddress(port_analyzer));
            serverSocket = ss;
            logger.info("HumaCount5D: server socket open on port {}", port_analyzer);
        }

        try {
            logger.info("HumaCount5D: waiting for analyzer connection on port {}", port_analyzer);
            Socket clientSocket = serverSocket.accept();
            logger.info("HumaCount5D: analyzer connected from {}", clientSocket.getRemoteSocketAddress());

            this.socket       = clientSocket;
            this.inputStream  = clientSocket.getInputStream();
            this.outputStream = clientSocket.getOutputStream();

            listenForIncomingMessages();

            logger.info("HumaCount5D: analyzer disconnected");

        } catch (IOException e) {
            if (this.listening.get()) {
                logger.error("ERROR startHL7Server: {}", e.getMessage());
            }
        } finally {
            closeClientSocket();
        }
    }

    /**
     * Reads MLLP-framed HL7 messages from the analyzer connection and processes each one.
     */
    private void listenForIncomingMessages() {
        logger.info("HumaCount5D: listenForIncomingMessages started");
        while (this.listening.get()) {
            try {
                if (this.inputStream == null) break;

                String receivedMessage = Connect_util.readMLLPMessage(this.inputStream);

                if (receivedMessage == null) {
                    logger.info("HumaCount5D: connection closed by analyzer (null message)");
                    break;
                }
                if (receivedMessage.isEmpty()) {
                    // 1.0.5 : readMLLPMessage() renvoie "" (jamais null) sur fin de flux.
                    // Un "continue" ici bouclait a l'infini des que l'automate
                    // se deconnectait. On ferme la session et on repart en accept().
                    logger.info("HumaCount5D: end of stream / empty MLLP frame, closing session");
                    break;
                }

                logger.info("HumaCount5D: received HL7 message:\n{}",
                    receivedMessage.replace("\r", "<CR>\n"));

                String responseMessage = processAnalyzerMsg(receivedMessage);

                // 1.0.6 : l'acquittement rendu a l'automate reflete la reponse du LIS.
                // Avant cette version, "MSA|AA" etait renvoye quoi qu'il arrive : un
                // resultat perdu etait annonce comme transmis.
                boolean accepted = lisAccepted(responseMessage);
                String ackCode   = accepted ? "AA" : "AE";
                if (!accepted) {
                    logger.warn("HumaCount5D: LabBook did NOT accept the result, answering MSA|AE to the analyzer. LIS response: {}",
                        responseMessage == null ? "null" : responseMessage.replace("\r", "<CR>\n"));
                }

                String msgId  = extractMsgControlId(receivedMessage);
                String ackMsg = buildAck(msgId, ackCode);
                String framed = Connect_util.encapsulateHL7Message(ackMsg);
                this.outputStream.write(framed.getBytes(StandardCharsets.UTF_8));
                this.outputStream.flush();
                logger.info("HumaCount5D: ACK sent to analyzer (msgId={}, code={})", msgId, ackCode);

            } catch (IOException e) {
                logger.error("ERROR listenForIncomingMessages: {}", e.getMessage());
                break;
            }
        }
        logger.info("HumaCount5D: listenForIncomingMessages stopped");
    }

    /**
     * Routes an incoming HL7 message to the appropriate LAB flow.
     * Only ORU^R01 (hematology results) is supported.
     */
    private String processAnalyzerMsg(String hl7Message) {
        logger.info("HumaCount5D processAnalyzerMsg:\n{}", hl7Message.replace("\r", "<CR>\n"));

        if (hl7Message == null || hl7Message.isEmpty() || !hl7Message.startsWith("MSH")) {
            logger.info("Invalid HL7 payload (missing MSH), ignoring");
            return null;
        }

        hl7Message = hl7Message.replace("\r\n", "\r").replace("\n", "\r");

        // Extract MSH-9 (message type)
        String msh9 = "";
        for (String seg : hl7Message.split("\\r")) {
            if (seg.startsWith("MSH|")) {
                String[] f = seg.split("\\|", -1);
                if (f.length > 9) {
                    // MSH split by "|": f[0]=MSH f[1]=^~\& f[2]=SendingApp ...
                    // f[8] = MSH-9 (message type e.g. "ORU^R01")
                    // f[9] = MSH-10 (message control ID)
                    String[] parts = f[8].split("\\^", -1);
                    String msgCode = (parts.length > 0) ? parts[0] : "";
                    String trig    = (parts.length > 1) ? parts[1] : "";
                    msh9 = msgCode + "_" + trig;
                }
                break;
            }
        }
        logger.info("HumaCount5D MSH-9 type: {}", msh9);

        if ("ORU_R01".equals(msh9)) {
            return lab29(hl7Message);
        } else {
            logger.info("HumaCount5D: unsupported HL7 message type: {}", msh9);
            return null;
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void closeClientSocket() {
        try { if (inputStream  != null) { inputStream.close();  inputStream  = null; } } catch (Exception ignore) {}
        try { if (outputStream != null) { outputStream.close(); outputStream = null; } } catch (Exception ignore) {}
        try { if (socket       != null && !socket.isClosed()) { socket.close(); socket = null; } } catch (Exception ignore) {}
    }

    @Override
    public void stopListening() {
        logger.info("HumaCount5D: stopListening called");
        this.listening.set(false);
        closeClientSocket();
        try { if (serverSocket != null && !serverSocket.isClosed()) { serverSocket.close(); } } catch (Exception ignore) {}
        serverSocket = null;   // 1.0.4 : force un bind neuf au prochain listenDevice()
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        logger.info("HumaCount5D: stopped");
    }

    @Override
    public Analyzer copy() {
        AnalyzerHumaCount5D inst = new AnalyzerHumaCount5D();
        inst.setVersion(this.version);
        inst.setId_analyzer(this.id_analyzer);
        inst.setUrl_upstream_lab27(this.url_upstream_lab27);
        inst.setUrl_upstream_lab29(this.url_upstream_lab29);
        inst.setType_cnx(this.type_cnx);
        inst.setType_msg(this.type_msg);
        inst.setArchive_msg(this.archive_msg);
        inst.setOperationMode(this.operation_mode);
        inst.setMode(this.mode);
        inst.setIp_analyzer(this.ip_analyzer);
        inst.setPort_analyzer(this.port_analyzer);
        inst.setMappingPath(this.mappingPath);
        return inst;
    }
}
