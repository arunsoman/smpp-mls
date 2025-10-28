# smpp-mls (Sprint 1)

This repository contains a Spring Boot skeleton for the Afghan SMPP Gateway project.

Sprint 1 implemented:
- A1: Project skeleton & infra (Spring Boot, Maven, Java 21)
- A2: Externalized configuration via `application.yml` and `SmppProperties` binding
- A3: Basic SMPP session manager (`SocketSmppSessionManager`) that attempts a TCP connect to each configured session and reports session health

Notes:
- The session manager currently performs a TCP connect as a lightweight "bind" check. For a production SMPP implementation replace this with CloudHopper wiring to perform true SMPP `bind_transceiver` and `enquire_link` PDUs.
- CloudHopper dependencies are commented in `pom.xml`; uncomment and set versions when ready.

How to run (Windows PowerShell):

```powershell
mvnw package
java -jar target\smpp-mls-0.1.0.jar
```

Default configuration is in `src/main/resources/application.yml`. Update operator host/port and credentials via environment variables or externalized config when deploying.

Next recommended tasks:
- Wire CloudHopper for real SMPP binds and PDUs
- Implement REST API submission endpoint and persistence
- Add unit and integration tests (SMPPSim)

