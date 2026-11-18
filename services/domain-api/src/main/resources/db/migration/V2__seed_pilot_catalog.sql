-- =============================================================================
-- Kanjora Domain API - Seed Pilot Catalog V2 (B05, B06, B13)
-- Data-driven initial pilot categories: Programación Backend <-> Inglés Técnico
-- =============================================================================

-- 1. Categories
INSERT INTO skill_categories (category_id, slug, name, is_active) VALUES
    ('10000000-0000-4000-8000-000000000001', 'tecnologia', 'Tecnología y Software', TRUE),
    ('10000000-0000-4000-8000-000000000002', 'idiomas', 'Idiomas Aplicados', TRUE)
ON CONFLICT (slug) DO NOTHING;

-- 2. Skills
INSERT INTO skills (skill_id, category_id, slug, name, description, is_active) VALUES
    (
        '20000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001',
        'programacion-backend',
        'Programación Backend',
        'Diseño de APIs, bases de datos relacionales, pruebas y arquitectura práctica.',
        TRUE
    ),
    (
        '20000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000002',
        'ingles-tecnico',
        'Inglés Técnico',
        'Comunicación oral y escrita para entornos de ingeniería, entrevistas y revisiones.',
        TRUE
    )
ON CONFLICT (slug) DO NOTHING;

-- 3. Levels per skill
INSERT INTO skill_levels (level_id, skill_id, code, name, rank_order, is_active) VALUES
    -- Programación Backend
    ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'BASICO', 'Básico', 1, TRUE),
    ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'INTERMEDIO', 'Intermedio', 2, TRUE),
    ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'AVANZADO', 'Avanzado', 3, TRUE),
    -- Inglés Técnico
    ('30000000-0000-4000-8000-000000000011', '20000000-0000-4000-8000-000000000002', 'A2', 'A2 - Pre-intermedio', 1, TRUE),
    ('30000000-0000-4000-8000-000000000012', '20000000-0000-4000-8000-000000000002', 'B1', 'B1 - Intermedio', 2, TRUE),
    ('30000000-0000-4000-8000-000000000013', '20000000-0000-4000-8000-000000000002', 'B2', 'B2 - Intermedio Alto', 3, TRUE),
    ('30000000-0000-4000-8000-000000000014', '20000000-0000-4000-8000-000000000002', 'C1', 'C1 - Avanzado', 4, TRUE)
ON CONFLICT (skill_id, code) DO NOTHING;

-- 4. Topics per skill
INSERT INTO skill_topics (topic_id, skill_id, code, name, is_active) VALUES
    -- Programación Backend
    ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'rest-apis', 'Diseño de APIs REST y OpenAPI', TRUE),
    ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'sql-postgresql', 'Modelado relacional y SQL en PostgreSQL', TRUE),
    ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'testing-backend', 'Pruebas unitarias e integración', TRUE),
    ('40000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000001', 'git-code-reviews', 'Flujos Git y Code Reviews', TRUE),
    -- Inglés Técnico
    ('40000000-0000-4000-8000-000000000011', '20000000-0000-4000-8000-000000000002', 'technical-interviews', 'Entrevistas técnicas en inglés', TRUE),
    ('40000000-0000-4000-8000-000000000012', '20000000-0000-4000-8000-000000000002', 'daily-standups', 'Dailies y comunicación en equipos ágiles', TRUE),
    ('40000000-0000-4000-8000-000000000013', '20000000-0000-4000-8000-000000000002', 'pr-reviews-async', 'Feedback técnico y Pull Requests por escrito', TRUE),
    ('40000000-0000-4000-8000-000000000014', '20000000-0000-4000-8000-000000000002', 'architecture-presentations', 'Explicación de arquitectura y trade-offs', TRUE)
ON CONFLICT (skill_id, code) DO NOTHING;

-- 5. Free Methodology & Basic Session Guides (B06, B13)
INSERT INTO study_guides (guide_id, skill_id, level_id, guide_type, title, content_markdown, is_free, is_active) VALUES
    (
        '50000000-0000-4000-8000-000000000001',
        NULL,
        NULL,
        'METHODOLOGY',
        'Metodología Kanjora de Enseñanza Recíproca (4 Pasos)',
        E'# Estructura de cada bloque de enseñanza\n1. **Objetivo claro (2 min):** Qué podrá hacer tu pareja al terminar el bloque.\n2. **Explicación concreta (8 min):** Concepto esencial con un ejemplo real.\n3. **Práctica guiada (15 min):** Tu pareja aplica lo aprendido mientras das feedback.\n4. **Comprobación (5 min):** Verificar comprensión con un ejercicio breve de cierre.',
        TRUE,
        TRUE
    ),
    (
        '50000000-0000-4000-8000-000000000002',
        '20000000-0000-4000-8000-000000000001',
        '30000000-0000-4000-8000-000000000002',
        'BASIC_STUDY_ROUTE',
        'Guía básica: Diseño de APIs REST y contratos claros',
        E'# Guía de sesión: APIs REST\n- Diferencia entre recursos, verbos HTTP y códigos 2xx/4xx.\n- Diseño de errores RFC 9457 e idempotencia básica.\n- Ejercicio práctico: modelar un endpoint de creación seguro ante reintentos.',
        TRUE,
        TRUE
    ),
    (
        '50000000-0000-4000-8000-000000000003',
        '20000000-0000-4000-8000-000000000002',
        '30000000-0000-4000-8000-000000000012',
        'BASIC_STUDY_ROUTE',
        'Guía básica: Explicar decisiones técnicas en Inglés (B1)',
        E'# Guía de sesión: Technical English B1\n- Conectores de causa/efecto para explicar trade-offs.\n- Simulación de pregunta de entrevista sobre un bug resuelto.\n- Checklist de pronunciación y vocabulario clave de backend.',
        TRUE,
        TRUE
    )
ON CONFLICT (guide_id) DO NOTHING;

