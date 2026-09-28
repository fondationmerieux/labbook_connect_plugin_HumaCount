#!/usr/bin/env python3
"""
Simulation HumaCount 5D — envoi d'un message ORU^R01 vers LabBook Connect.

Simule exactement ce que l'automate envoie :
  - TCP client se connecte sur l'IP/port du serveur LabBook Connect
  - Envoie un message ORU^R01 encapsulé en MLLP (UTF-8)
  - Attend l'ACK de LabBook Connect

Usage:
  python3 simulate_humacount5d_send_result.py [ip] [port]

Valeurs par défaut : 127.0.0.1:7501
"""

import socket
import sys
import time

# MLLP delimiters
SB = b'\x0b'   # Start Block
EB = b'\x1c'   # End Block
CR = b'\x0d'   # Carriage Return

def mllp_wrap(hl7_msg: str) -> bytes:
    return SB + hl7_msg.encode('utf-8') + EB + CR

def mllp_unwrap(data: bytes) -> str:
    if data.startswith(SB):
        data = data[1:]
    if data.endswith(CR):
        data = data[:-1]
    if data.endswith(EB):
        data = data[:-1]
    return data.decode('utf-8', errors='replace')

# ── Message ORU^R01 HumaCount 5D (NFS de base) ───────────────────────────────
# Reproduit le format exact du manuel d'interface V1
# OBX-11 = "F" (final) en position f[11] — natif dans le message automate

hl7_msg = (
    "MSH|^~\\&|HC5D|HUMAN|||20260907120000||ORU^R01|HC5D-20260907-001|P|2.3.1||||||UNICODE\r"
    "PID|1||TEST-0001^^^^MR||TEST^PATIENT||19850315000000|Male\r"
    "PV1|1|Outpatient|Hematologie^1^1|||||||||||||||||Assurance\r"
    "OBR|1||TEST-0001|01001^Automated Count^99MRC||20260907115000|20260907115500|||DOCTOR^TEST||||20260907115800||||||||||HM||||||||technicien\r"
    # Métadonnées (IS) — doivent être ignorées par le plugin (OBX-2 != NM)
    "OBX|1|IS|02001^Loading Mode^99MRC||O||||||F\r"
    "OBX|2|IS|02002^Blood Mode^99MRC||W||||||F\r"
    "OBX|3|IS|02003^Test Mode^99MRC||CBC+DIFF||||||F\r"
    "OBX|4|NM|30525-0^Age^LN||41|yr|||||F\r"
    "OBX|5|IS|09001^Remark^99MRC||||||||F\r"
    "OBX|6|IS|03001^Ref Group^99MRC||Adult male||||||F\r"
    # Résultats NFS (NM) — doivent être mappés
    "OBX|7|NM|6690-2^WBC^LN||6.82|10*9/L|4.00-10.00||||F\r"
    "OBX|8|NM|770-8^NEU%^LN||62.3|%|50.0-70.0||||F\r"
    "OBX|9|NM|736-9^LYM%^LN||29.4|%|20.0-40.0||||F\r"
    "OBX|10|NM|5905-5^MON%^LN||6.1|%|3.0-12.0||||F\r"
    "OBX|11|NM|713-8^EOS%^LN||1.8|%|0.5-5.0||||F\r"
    "OBX|12|NM|706-2^BAS%^LN||0.4|%|0.0-1.0||||F\r"
    "OBX|13|NM|751-8^NEU#^LN||4.25|10*9/L|2.00-7.00||||F\r"
    "OBX|14|NM|731-0^LYM#^LN||2.01|10*9/L|0.80-4.00||||F\r"
    "OBX|15|NM|742-7^MON#^LN||0.42|10*9/L|0.12-1.20||||F\r"
    "OBX|16|NM|711-2^EOS#^LN||0.12|10*9/L|0.02-0.50||||F\r"
    "OBX|17|NM|704-7^BAS#^LN||0.02|10*9/L|0.00-0.10||||F\r"
    "OBX|18|NM|789-8^RBC^LN||4.92|10*12/L|4.00-5.50||||F\r"
    "OBX|19|NM|718-7^HGB^LN||148|g/L|120-160||||F\r"
    "OBX|20|NM|4544-3^HCT^LN||44.1|%|40.0-54.0||||F\r"
    "OBX|21|NM|787-2^MCV^LN||89.6|fL|80.0-100.0||||F\r"
    "OBX|22|NM|785-6^MCH^LN||30.1|pg|27.0-34.0||||F\r"
    "OBX|23|NM|786-4^MCHC^LN||336|g/L|320-360||||F\r"
    "OBX|24|NM|788-0^RDW-CV^LN||13.2|%|11.0-16.0||||F\r"
    "OBX|25|NM|777-3^PLT^LN||224|10*9/L|100-300||||F\r"
    "OBX|26|NM|32623-1^MPV^LN||9.8|fL|6.5-12.0||||F\r"
)

def send_result(ip='127.0.0.1', port=7501):
    print(f"Connexion à LabBook Connect ({ip}:{port})...")
    try:
        with socket.create_connection((ip, port), timeout=10) as s:
            print("Connecté.")
            payload = mllp_wrap(hl7_msg)
            s.sendall(payload)
            print(f"Message ORU^R01 envoyé ({len(payload)} octets)")

            # Attendre ACK
            time.sleep(1)
            s.settimeout(10)
            data = b''
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
                if EB in data:
                    break

            if data:
                ack = mllp_unwrap(data)
                print(f"\nACK reçu :\n{ack.replace(chr(13), chr(10))}")
                if 'MSA|AA' in ack:
                    print("\n✅ ACK AA — message accepté par LabBook Connect")
                elif 'MSA|AE' in ack:
                    print("\n❌ ACK AE — erreur")
                else:
                    print("\n⚠️  ACK inattendu")
            else:
                print("\n⚠️  Pas de réponse reçue")

    except ConnectionRefusedError:
        print(f"❌ Connexion refusée sur {ip}:{port} — LabBook Connect est-il démarré ?")
    except socket.timeout:
        print("⚠️  Timeout — pas de réponse dans les 10 secondes")
    except Exception as e:
        print(f"❌ Erreur : {e}")

if __name__ == '__main__':
    ip   = sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1'
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7501
    send_result(ip, port)
