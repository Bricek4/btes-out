CREATE INDEX shares_owner_resource ON shares(owner_id, resource_type, resource_id);
CREATE INDEX artifacts_task_current ON artifacts(task_id, current_version) WHERE current_version > 0;
CREATE INDEX tasks_project_created_active ON tasks(project_id, created_at DESC) WHERE deleted_at IS NULL;
