# Kanjora Backend (MVP Piloto)

Backend del piloto **Kanjora**: plataforma de intercambio recíproco de conocimiento (**Programación Backend ↔ Inglés Técnico**).

## Arquitectura y Límites de Servicio

* **API de Dominio (`services/domain-api` — Java 21 / Spring Boot 3.3):** Dueña del esquema `domain` en PostgreSQL (`kanjora_domain_app`). Gestiona catálogo configurable como datos, perfiles, disponibilidad horaria, habilitación en 3 pasos con revisión humana de mini clase, emparejamiento recíproco explicable, propuestas, recepción idempotente de hechos de sesión, confirmación bilateral, historial y métricas del piloto.
* **API de Sesiones (`services/sessions-api` — Go 1.22):** Dueña del esquema `sessions` en PostgreSQL (`kanjora_sessions_app`). Valida propuestas contra Java por HTTP M2M antes de agendar, gestiona acuerdos previos, máquina de estados de sesión, idempotencia de creación/reprogramación, emisión de tokens LiveKit en servidor de corta duración (20 min) solo para los 2 participantes autorizados y envío confiable (*outbox*) de hechos observables hacia Java.
* **Identidad compartida (`B01`):** Keycloak (`kanjora-realm.json`) emite tokens JWT verificables por ambos servicios (`aud: kanjora-api`, `sub` canónico) y tokens M2M (`client_credentials`) para llamadas internas `Go → Java`.
* **Reglas congeladas fuera del piloto:** No se activan saldos de créditos, prioridad VIP ni sanciones automáticas. Solo se registra un hecho factual en `confirmed_exchange_facts_log` tras confirmación bilateral.

## Contratos OpenAPI 3.1 (`B02`)

* [domain-api-v1.yaml](contracts/openapi/domain-api-v1.yaml)
* [sessions-api-v1.yaml](contracts/openapi/sessions-api-v1.yaml)

## 📚 Documentación Técnica y de Ingeniería de Software

El proyecto cuenta con un dossier formal de ingeniería bajo estándares IEEE 830, ISO/IEC 25010, UML y C4:

* 📖 **[Dossier Completo de Documentación](documentacion/README.md)** (Índice general)
  * 🏛️ **[01. Visión del Producto y Arquitectura de Software](documentacion/01_ARQUITECTURA_Y_VISION.md)** (Modelos C4, ADRs y aislamiento multi-schema)
  * 📋 **[02. Especificación de Requisitos y Matriz de Trazabilidad (SRS & RTM)](documentacion/02_REQUISITOS_Y_TRAZABILIDAD.md)** (18 RFs con BDD/Gherkin, RNFs ISO 25010 y RTM completa)
  * 🎬 **[03. Especificación de Casos de Uso y Diagramas de Secuencia](documentacion/03_CASOS_DE_USO_Y_SECUENCIA.md)** (Fichas RUP/Cockburn y diagramas Mermaid)
  * 🗄️ **[04. Modelo Entidad-Relación y Diccionario de Datos](documentacion/04_MODELO_DATOS_Y_DICCIONARIO.md)** (MER físico y diccionario de las 20 tablas)
  * 🛡️ **[05. Seguridad, Identidad y Gobernanza de APIs](documentacion/05_SEGURIDAD_APIS_Y_GOBERNANZA.md)** (OIDC Keycloak, M2M, WebRTC LiveKit, RFC 9457 e Idempotencia)
  * 🚀 **[06. Plan de Calidad, Testing, CI/CD y DevOps](documentacion/06_PLAN_TESTING_CI_CD_Y_DEVOPS.md)** (Pirámide de tests, GitHub Actions y producción)
  * 📖 **[07. Manual de Usuario y Guía Operativa](documentacion/07_MANUAL_DE_USUARIO.md)** (Guía paso a paso para participantes y rúbrica para revisores docentes)
  * 📄 **[Documento Técnico Compilado Maestro](documentacion/DOCUMENTACION_TECNICA_KANJORA.md)** (Dossier integrado unificado ~60 KB)

## Arranque Local (`B03`, `B16`)

1. **Levantar infraestructura aislada (PostgreSQL 16 + Keycloak + LiveKit):**
   ```powershell
   docker compose up -d
   ```
   * PostgreSQL ejecuta [init-isolation.sql](infra/postgres/init-isolation.sql) aislando esquemas `domain` y `sessions`.
   * Keycloak importa [kanjora-realm.json](infra/keycloak/kanjora-realm.json) con usuarios `alice` (`alice_dev_pwd`), `bob` (`bob_dev_pwd`) y `reviewer` (`reviewer_dev_pwd`).

2. **Ejecutar pruebas y levantar API de Dominio (Java):**
   ```powershell
   cd services/domain-api
   mvn test
   mvn spring-boot:run
   ```

3. **Ejecutar pruebas y levantar API de Sesiones (Go):**
   ```powershell
   cd services/sessions-api
   go mod tidy
   go test ./...
   go run .
   ```

4. **Ejecutar recorrido E2E completo (Pasos 1–9 + casos de rechazo):**
   ```powershell
   .\scripts\e2e-pilot-journey.ps1
   ```
