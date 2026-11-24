-- 운영 DB에서 앱 역할(pawbridge_app) 권한을 GRANT 문으로 뽑는다. 읽기 전용 조회다.
-- 결과를 app-grants.sql 머리말 아래에 그대로 붙인다(README의 "권한 다시 뽑기").
-- payment 스키마(시험 환경에서 안 만듦)와 운영에만 남은 flyway_schema_history 표는 뺀다.
WITH tables AS (
    SELECT table_schema || '.' || table_name AS name,
           string_agg(privilege_type, ', ' ORDER BY array_position(
               ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'], privilege_type::text)) AS privileges
    FROM information_schema.role_table_grants
    WHERE grantee = 'pawbridge_app' AND table_schema LIKE 'pawbridge\_%' AND table_schema <> 'pawbridge_payment'
      AND table_name <> 'flyway_schema_history'
    GROUP BY 1
), sequences AS (
    SELECT n.nspname || '.' || c.relname AS name,
           string_agg(p.privilege_type, ', ' ORDER BY array_position(ARRAY['SELECT', 'UPDATE', 'USAGE'], p.privilege_type)) AS privileges
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(c.relacl) p
    WHERE c.relkind = 'S' AND p.grantee = 'pawbridge_app'::regrole AND n.nspname LIKE 'pawbridge\_%' AND n.nspname <> 'pawbridge_payment'
    GROUP BY 1
), schemas AS (
    SELECT nspname AS name, string_agg(p.privilege_type, ', ' ORDER BY p.privilege_type) AS privileges
    FROM pg_namespace, aclexplode(nspacl) p
    WHERE p.grantee = 'pawbridge_app'::regrole AND nspname LIKE 'pawbridge\_%' AND nspname <> 'pawbridge_payment'
    GROUP BY 1
)
SELECT line FROM (
    SELECT 1 AS part, name, format('GRANT %s ON SCHEMA %s TO pawbridge_app;', privileges, name) AS line FROM schemas
    UNION ALL
    SELECT 2, name, format('GRANT %s ON %s TO pawbridge_app;', privileges, name) FROM tables
    UNION ALL
    SELECT 3, name, format('GRANT %s ON SEQUENCE %s TO pawbridge_app;', privileges, name) FROM sequences
) grants
ORDER BY part, name;
