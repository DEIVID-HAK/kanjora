-- =============================================================================
-- Kanjora PostgreSQL Logical Isolation (B03)
-- Enforces strict ownership:
--   * domain-api (Java)   -> user: kanjora_domain_app   -> schema: domain
--   * sessions-api (Go)   -> user: kanjora_sessions_app -> schema: sessions
-- Cross-schema reads and writes are explicitly revoked at the database engine layer.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Service-specific application roles (replace dev passwords via secrets in staging/prod)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kanjora_domain_app') THEN
        CREATE ROLE kanjora_domain_app WITH LOGIN PASSWORD 'kanjora_domain_dev_pwd';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kanjora_sessions_app') THEN
        CREATE ROLE kanjora_sessions_app WITH LOGIN PASSWORD 'kanjora_sessions_dev_pwd';
    END IF;
END
$$;

-- 2. Isolated schemas owned exclusively by each service role
CREATE SCHEMA IF NOT EXISTS domain AUTHORIZATION kanjora_domain_app;
CREATE SCHEMA IF NOT EXISTS sessions AUTHORIZATION kanjora_sessions_app;

-- 3. Default search_path per role so Flyway / Go migrations target only their own schema
ALTER ROLE kanjora_domain_app SET search_path = domain, pg_catalog;
ALTER ROLE kanjora_sessions_app SET search_path = sessions, pg_catalog;

-- 4. Lock down default public access and cross-schema access
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA domain FROM PUBLIC;
REVOKE ALL ON SCHEMA sessions FROM PUBLIC;

REVOKE ALL ON SCHEMA sessions FROM kanjora_domain_app;
REVOKE ALL ON SCHEMA domain FROM kanjora_sessions_app;

GRANT USAGE, CREATE ON SCHEMA domain TO kanjora_domain_app;
GRANT USAGE, CREATE ON SCHEMA sessions TO kanjora_sessions_app;

-- 5. Default privileges on future tables/sequences created inside each schema
ALTER DEFAULT PRIVILEGES FOR ROLE kanjora_domain_app IN SCHEMA domain
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO kanjora_domain_app;
ALTER DEFAULT PRIVILEGES FOR ROLE kanjora_domain_app IN SCHEMA domain
    GRANT USAGE, SELECT ON SEQUENCES TO kanjora_domain_app;

ALTER DEFAULT PRIVILEGES FOR ROLE kanjora_sessions_app IN SCHEMA sessions
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO kanjora_sessions_app;
ALTER DEFAULT PRIVILEGES FOR ROLE kanjora_sessions_app IN SCHEMA sessions
    GRANT USAGE, SELECT ON SEQUENCES TO kanjora_sessions_app;

