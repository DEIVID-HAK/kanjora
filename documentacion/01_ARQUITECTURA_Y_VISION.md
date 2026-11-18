# Kanjora — Documento 01: Visión del Producto y Arquitectura de Software
**Plataforma Distribuida de Trueque y Aprendizaje Recíproco 1:1**

---

## 1. Visión del Producto y Análisis de Negocio

### 1.1 El Problema de Mercado
En el ámbito del aprendizaje de tecnología e idiomas para ingeniería, las soluciones convencionales presentan fallas estructurales:
* **Cursos grabados pasivos:** Altas tasas de abandono (>90%) por carecer de interactividad humana y retroalimentación adaptativa.
* **Tutorías privadas pagadas:** Barrera económica elevada ($30–$80 USD/hora), excluyendo a desarrolladores talentosos de regiones en desarrollo.
* **Plataformas de intercambio asimétricas / Pay-to-Win:** Modelos basados en créditos comerciales donde los usuarios con mayor capacidad económica compran la atención de los mejores docentes, desvirtuando el intercambio colaborativo.

### 1.2 La Propuesta de Valor de Kanjora
**Kanjora** democratiza el acceso al conocimiento técnico avanzado mediante el **trueque directo 1 a 1**:
1. **Reciprocidad Obligatoria:** Toda persona que recibe mentoría en una habilidad debe ofrecer mentoría en otra que domine.
2. **Piloto Estratégico de Lanzamiento:** **Programación Backend $\leftrightarrow$ Inglés Técnico**. Un programador sénior hispanohablante ayuda a un colega a diseñar arquitecturas de microservicios o modelar bases de datos, y a cambio recibe práctica guiada de conversación en inglés para entrevistas y reuniones técnicas.
3. **Cero Especulación Financiera:** Los créditos recompensan la constancia y desbloquean personalización de rutas en fases posteriores; **nunca compran clases ni alteran el orden de búsqueda**.

---

## 2. Decisiones y Trade-offs de Arquitectura (ADR)

| Decisión Arquitectónica | Alternativa Descartada | Justificación Técnica del Trade-off |
| :--- | :--- | :--- |
| **Microservicios Políglotas (Java 21 + Go)** | Monolito único en un solo lenguaje | Java (Spring Boot) ofrece robustez en modelado de dominio DDD, validaciones complejas de catálogo y seguridad corporativa OIDC. Go provee huella de memoria mínima (~25 MB), alta concurrencia nativa (goroutines) y latencia ultra baja para la orquestación de sockets y tokens WebRTC. |
| **Aislamiento Lógico Multi-Schema en PostgreSQL** | Una base de datos física por microservicio | En etapa de MVP, una única instancia PostgreSQL 16 con esquemas segregados (`domain` y `sessions`) y roles independientes (`kanjora_domain_app`, `kanjora_sessions_app`) reduce costos operativos y complejidad de infraestructura al 50%, manteniendo la independencia arquitectónica sin cross-schema queries. |
| **LiveKit SFU (Selective Forwarding Unit)** | WebRTC Mesh peer-to-peer puro | El modelo Mesh satura el ancho de banda de subida del cliente al requerir $N-1$ streams salientes. Un SFU centralizado (LiveKit) reduce el consumo de red en los participantes y garantiza telemetría precisa de caídas y desconexiones mediante webhooks. |
| **Transaccional Outbox HTTP entre Servicios** | Message Broker distribuido (Kafka/RabbitMQ) | Introducir Kafka o RabbitMQ en la fase piloto agregaría sobrecarga operacional innecesaria (Zookeeper/KRaft, clustering, monitoreo). La tabla `session_outbox_facts` con un worker asíncrono en Go y cabecera `Idempotency-Key` en Java ofrece semántica *At-Least-Once Delivery* sin componentes extra. |
| **OpenID Connect Centralizado (Keycloak)** | Sistema propio de autenticación con tablas `users` y passwords | Elimina el riesgo crítico de seguridad asociado al almacenamiento y hashing de credenciales. Permite SSO, rotación automática de claves RSA y compatibilidad nativa con tokens JWT estándar (RFC 7519). |

---

## 3. Diagramas Arquitectónicos C4

### 3.1 Nivel 1: Diagrama de Contexto del Sistema

```mermaid
flowchart TD
    subgraph Usuarios
        UA["Participante A\n(Enseñante Backend / Aprendiz Inglés)"]
        UB["Participante B\n(Enseñante Inglés / Aprendiz Backend)"]
        REV["Comité Revisor Humano\n(Auditores de Mini-Clases)"]
    end

    subgraph KanjoraBoundary ["Frontera del Sistema Kanjora"]
        KCore["Sistema Distribuido Kanjora\n(Gestión de Catálogo, Matching, Videollamada y Cierre)"]
    end

    subgraph ExternalSystems ["Sistemas de Infraestructura Especializada"]
        IdP["Keycloak 26.0\n(Servidor de Identidad OIDC / OAuth2)"]
        SFU["LiveKit Server 1.7\n(Servidor SFU WebRTC de Audio y Video)"]
    end

    UA -->|"1. Inicia sesión OIDC, completa perfil y disponibilidad"| KCore
    UB -->|"1. Inicia sesión OIDC, acepta propuesta y firma acuerdo"| KCore
    REV -->|"Evalúa mini-clases bajo rúbrica pedagógica"| KCore

    KCore -->|"Valida firma y vigencia de Access Tokens JWT"| IdP
    KCore -->|"Solicita creación de salas y emite tokens efímeros"| SFU
    UA -.->|"Transmisión de Audio/Video en Tiempo Real (UDP 7882)"| SFU
    UB -.->|"Transmisión de Audio/Video en Tiempo Real (UDP 7882)"| SFU
```

### 3.2 Nivel 2: Diagrama de Contenedores

```mermaid
flowchart TB
    subgraph ClientLayer ["Capa Cliente"]
        AngularApp["Frontend SPA\n(Angular 18+ / TypeScript)\nEnrutamiento, Formularios reactivos,\nCliente LiveKit WebRTC SDK"]
    end

    subgraph GatewayAuthLayer ["Capa de Identidad y Seguridad"]
        KeycloakCont["Contenedor: kanjora-keycloak\n(Keycloak 26.0)\nPuerto: 8180\nEmisión de JWT OIDC"]
    end

    subgraph AppServicesLayer ["Capa de Microservicios Backend"]
        DomainService["Microservicio: domain-api\n(Java 21 / Spring Boot 3.x)\nPuerto: 8080\nResponsabilidades: Perfiles, Habilitación Docente,\nCatálogo, Matching Explicable, Cierre Bilateral"]
        SessionsService["Microservicio: sessions-api\n(Go 1.22+)\nPuerto: 8081\nResponsabilidades: Agenda, Máquina de Estados,\nFirma de Acuerdos, Tokens LiveKit, Outbox"]
    end

    subgraph MediaServicesLayer ["Capa de Streaming de Medios"]
        LiveKitCont["Contenedor: kanjora-livekit\n(LiveKit Server 1.7)\nPuertos: 7880 (HTTP), 7881 (TCP), 7882 (UDP)\nSFU de Audio/Video de Baja Latencia"]
    end

    subgraph DatabaseLayer ["Capa de Persistencia Relacional"]
        subgraph PostgresInstance ["Contenedor: kanjora-postgres (PostgreSQL 16)"]
            subgraph DomainSchema ["Esquema: domain (Dueño: kanjora_domain_app)"]
                TablesDomain[("14 Tablas de Dominio:\nskills, profiles, qualifications,\nproposals, resolutions, etc.")]
            end
            subgraph SessionsSchema ["Esquema: sessions (Dueño: kanjora_sessions_app)"]
                TablesSessions[("6 Tablas de Sesiones:\nsessions, blocks, agreements,\nevents, outbox, idempotency")]
            end
        end
    end

    AngularApp -->|"Autenticación / Tokens"| KeycloakCont
    AngularApp -->|"REST HTTPS /api/v1 (User JWT)"| DomainService
    AngularApp -->|"REST HTTPS /api/v1 (User JWT)"| SessionsService
    AngularApp -->|"WebSockets / WebRTC Media"| LiveKitCont

    SessionsService -->|"M2M GET /internal/v1/.../validation (Service JWT)"| DomainService
    SessionsService -->|"M2M POST /internal/v1/.../session-facts (Idempotente)"| DomainService

    SessionsService -->|"Genera token de sala firmado (server-sdk-go)"| LiveKitCont
    LiveKitCont -.->|"Webhooks de presencia HTTP POST"| SessionsService

    DomainService -->|"JDBC (search_path=domain)"| TablesDomain
    SessionsService -->|"pgx pool (search_path=sessions)"| TablesSessions
```

### 3.3 Nivel 3: Diagrama de Componentes Internos de Domain API

```mermaid
flowchart TD
    subgraph DomainAPI ["Microservicio: domain-api (Spring Boot 3.x)"]
        subgraph SecurityFilter ["Filtro de Seguridad Spring"]
            SecConfig["SecurityConfig\n(Stateless, JwtDecoder Nimbus, Role Converter)"]
        end

        subgraph Controllers ["Capa de Controladores REST"]
            CatCtrl["CatalogController\n(/api/v1/catalog)"]
            ProfCtrl["ProfileController\n(/api/v1/profiles)"]
            QualCtrl["QualificationController\n(/api/v1/teaching-qualifications & /api/v1/admin/mini-class-submissions)"]
            MatchCtrl["MatchingController\n(/api/v1/matches)"]
            PropCtrl["ProposalController\n(/api/v1/exchange-proposals, /api/v1/exchanges & /internal/v1)"]
        end

        subgraph Services ["Capa de Servicios de Negocio"]
            ProfSvc["ProfileService\n(Validación de huso y slots)"]
            QualSvc["QualificationService\n(Gestión de intentos y rúbrica)"]
            MatchSvc["MatchingService\n(Normalización semanal UTC y Score)"]
            PropSvc["ProposalService\n(Validación de habilitaciones mutuas)"]
            ConfSvc["BilateralConfirmationService\n(Resolución y bitácora)"]
            FactSvc["SessionFactIngestionService\n(Ingestión idempotente)"]
        end

        subgraph Repositories ["Capa de Persistencia (Spring Data / JDBC)"]
            CatRepo["CatalogRepository"]
            ProfRepo["ProfileRepository"]
            QualRepo["QualificationRepository"]
            MatchRepo["MatchingRepository"]
            PropRepo["ProposalRepository"]
            ConfRepo["ConfirmationRepository"]
        end
    end

    SecConfig --> Controllers
    CatCtrl --> CatRepo
    ProfCtrl --> ProfSvc --> ProfRepo
    QualCtrl --> QualSvc --> QualRepo
    MatchCtrl --> MatchSvc --> MatchRepo
    PropCtrl --> PropSvc --> PropRepo
    PropCtrl --> ConfSvc --> ConfRepo
    PropCtrl --> FactSvc --> ConfRepo
```

---

## 4. Diagrama de Despliegue Físico y Redes

```mermaid
flowchart LR
    subgraph DockerHost ["Host de Despliegue (Docker Engine)"]
        subgraph BridgeNetwork ["Red Privada Docker: kanjora-net"]
            KPG["kanjora-postgres:5432\nPostgreSQL 16"]
            KKC["kanjora-keycloak:8180\nKeycloak 26.0"]
            KLK["kanjora-livekit:7880,7882\nLiveKit Server 1.7"]
            KDAPP["kanjora-domain-api:8080\nSpring Boot Container"]
            KSAPP["kanjora-sessions-api:8081\nGo Container"]
        end
    end

    subgraph InternetClient ["Clientes Externos"]
        Browser["Navegador Web del Usuario\n(Angular SPA)"]
    end

    Browser -->|"TCP 8080 (REST)"| KDAPP
    Browser -->|"TCP 8081 (REST)"| KSAPP
    Browser -->|"TCP 8180 (OIDC)"| KKC
    Browser -->|"TCP 7880 & UDP 7882 (WebRTC)"| KLK

    KDAPP -->|"TCP 5432 (JDBC)"| KPG
    KSAPP -->|"TCP 5432 (pgx)"| KPG
    KSAPP -->|"TCP 8080 (M2M REST)"| KDAPP
    KSAPP -->|"TCP 7880 (LiveKit API)"| KLK
    KLK -.->|"TCP 8081 (Webhooks)"| KSAPP
```
