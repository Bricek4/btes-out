ALTER TABLE task_approvals ADD COLUMN evidence_reference VARCHAR(2048);
ALTER TABLE task_approvals ADD CONSTRAINT approval_evidence_reference_format
  CHECK (evidence_reference IS NULL OR evidence_reference LIKE 'approval://screenshot-route/%');
