# Dossier de Documentación Técnica de Software — Proyecto Kanjora
**Repositorio Oficial de Especificación de Arquitectura, Requisitos y Modelo de Datos**

---

## 📚 Índice General de la Documentación

Este compendio documental reúne las especificaciones formales de ingeniería de software para la plataforma **Kanjora**, diseñadas bajo estándares de la industria (IEEE 830, ISO/IEC 25010, UML 2.5 y Modelo C4):

1. 🏛️ **[01. Visión del Producto y Arquitectura de Software](01_ARQUITECTURA_Y_VISION.md)**
   * Problema de mercado y propuesta de valor de Kanjora.
   * Registro de Decisiones de Arquitectura (ADRs) y justificación de trade-offs.
   * Modelos C4 completos: Contexto (Nivel 1), Contenedores (Nivel 2), Componentes internos (Nivel 3) y Despliegue Físico de Red (Nivel 4).
   * Aislamiento lógico multi-schema en PostgreSQL 16.

2. 📋 **[02. Especificación de Requisitos de Software y Matriz de Trazabilidad (SRS & RTM)](02_REQUISITOS_Y_TRAZABILIDAD.md)**
   * Requisitos Funcionales detallados (RF-01 al RF-18) con escenarios BDD / Gherkin.
   * Requisitos No Funcionales (RNF-01 al RNF-12) bajo la norma ISO/IEC 25010 con métricas verificables y métodos de prueba.
   * Matriz de Trazabilidad de Requisitos (RTM) con cobertura integral cruzando requisitos, casos de uso, servicios, contratos OpenAPI y tablas relacionales.

3. 🎬 **[03. Especificación de Casos de Uso y Diagramas de Secuencia](03_CASOS_DE_USO_Y_SECUENCIA.md)**
   * Diagrama General de Casos de Uso en notación UML.
   * Fichas técnicas completas bajo metodología RUP / Cockburn para CU-01 al CU-07.
   * Diagramas de secuencia dinámicos: Habilitación docente en 3 etapas, Algoritmo de Matching con normalización UTC, Agenda y validación M2M, Telemetría WebRTC en LiveKit y Confirmación bilateral Outbox.

4. 🗄️ **[04. Modelo Entidad-Relación y Diccionario de Datos](04_MODELO_DATOS_Y_DICCIONARIO.md)**
   * Diagrama Entidad-Relación físico completo en notación Mermaid.
   * Diccionario de datos exhaustivo para las 20 entidades de los esquemas `domain` y `sessions`.
   * Detalle de restricciones `CHECK`, claves primarias compuestas, índices parciales optimizados y reglas de integridad referencial en cascada.

5. 🛡️ **[05. Seguridad, Identidad y Gobernanza de APIs](05_SEGURIDAD_APIS_Y_GOBERNANZA.md)**
   * Modelo OIDC / OAuth2 con Keycloak 26.0 y especificación de claims JWT.
   * Doble capa de autorización: Tokens de Usuario final vs. Tokens de Servicio M2M (`ROLE_INTERNAL_SESSIONS_SERVICE`).
   * Seguridad en WebRTC: Tokens efímeros LiveKit de 15 minutos con restricción estricta de sala.
   * Manejo normalizado de errores (RFC 9457 Problem Details) e Idempotencia transaccional (RFC 7231).

6. 🚀 **[06. Plan de Calidad, Testing, CI/CD y DevOps](06_PLAN_TESTING_CI_CD_Y_DEVOPS.md)**
   * Estrategia de la Pirámide de Pruebas (Unitarias, Integración, Contrato M2M y E2E).
   * Arquitectura del pipeline automatizado de integración continua (GitHub Actions).
   * Plan de migración local $\rightarrow$ producción en la nube (LiveKit Cloud, Cloud SQL, Secret Manager).
   * Guía de observabilidad y métricas de negocio.

7. 📖 **[07. Manual de Usuario y Guía Operativa](07_MANUAL_DE_USUARIO.md)**
   * Guía ilustrada paso a paso para el participante (Registro, Perfil, Certificación docente, Matching, Agenda y Videollamada WebRTC).
   * Manual operativo para revisores docentes con rúbrica pedagógica formal en 4 pasos.
   * Preguntas frecuentes, guía de resolución de incidencias (FAQ / Troubleshooting) y glosario de términos.

---

## 🎯 Resumen de Valor Técnico: Por qué este Proyecto Destaca

| Factor Diferencial | Implementación Estándar (Otros Proyectos) | Implementación de Excelencia en Kanjora |
| :--- | :--- | :--- |
| **Algoritmo de Matching** | Filtro estático básico en memoria o simple `WHERE skill = 'x'`. | **Normalización temporal modular UTC ($0$ a $10079$)**, cruce de intervalos continuos y cálculo de afinidad explicable matemáticamente. |
| **Arquitectura de Datos** | Monolito o microservicios que comparten la misma base de datos sin restricciones de acceso. | **Aislamiento lógico estricto multi-schema** (`domain` y `sessions`) en PostgreSQL 16 con usuarios independientes y revocación mutua de permisos. |
| **Integración entre Servicios** | Llamadas HTTP síncronas frágiles sin tolerancia a fallas de red. | **Patrón Transaccional Outbox** con reintentos exponenciales y soporte estricto de cabeceras `Idempotency-Key`. |
| **Control de Calidad Docente** | Autodeclaración de habilidades sin filtros ni validaciones. | **Quality Gate en 3 fases:** Metodología pedagógica, prueba técnica objetiva y evaluación humana de mini-clase con rúbrica formal. |
| **Infraestructura de Medios** | Integración externa o enlaces manuales de Google Meet / Zoom. | **Servidor WebRTC SFU integrado (LiveKit)** con emisión de tokens de sala dinámicos y telemetría de presencia vía webhooks. |
