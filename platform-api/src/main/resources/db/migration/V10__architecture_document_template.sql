-- Make the built-in project document an architecture deliverable while preserving
-- the original version for reproducibility and audit history.
UPDATE templates t
   SET name = 'Architecture document'
  FROM skills s
 WHERE t.skill_id = s.id
   AND s.code = 'project-docs'
   AND t.visibility = 'PUBLIC'
   AND t.name = 'Project documentation';

WITH candidates AS (
    SELECT t.id AS template_id,
           COALESCE(t.owner_id, admin.id) AS created_by
      FROM templates t
      JOIN skills s ON s.id = t.skill_id
      JOIN LATERAL (
          SELECT u.id
            FROM users u
           WHERE u.organization_id = t.organization_id
             AND u.role = 'ADMIN'
             AND u.disabled_at IS NULL
           ORDER BY u.created_at, u.id
           LIMIT 1
      ) admin ON TRUE
     WHERE s.code = 'project-docs'
       AND t.name IN ('Architecture document', 'Project documentation copy')
       AND NOT EXISTS (
           SELECT 1 FROM template_versions v
            WHERE v.template_id = t.id AND v.ordinal = 2
       )
       AND EXISTS (
           SELECT 1 FROM template_versions v
            WHERE v.template_id = t.id
              AND v.ordinal = 1
              AND v.output_format = 'MARKDOWN'
              AND v.markdown_template = E'# {{title}}\n\n{{content}}'
       )
)
INSERT INTO template_versions(
    id, template_id, ordinal, output_format, parameter_schema, form_layout,
    allowed_sections, markdown_template, html_template, css, validation_rules,
    created_by, created_at
)
SELECT gen_random_uuid(), template_id, 2, 'MARKDOWN',
       '{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string","title":"文档标题","default":"项目架构文档"},"audience":{"type":"string","title":"阅读对象","default":"开发、测试与运维"}},"required":["title"]}'::jsonb,
       '{"columns":1}'::jsonb,
       '["overview","architecture","services","data","deployment","security","tradeoffs"]'::jsonb,
       E'# {{title}}\n\n{{content}}', NULL, NULL, '[]'::jsonb,
       created_by, now()
  FROM candidates;

