ALTER TABLE tasks
  ADD CONSTRAINT tasks_provider_model_fk
  FOREIGN KEY (provider_profile_id, model_id)
  REFERENCES provider_models(provider_profile_id, model_id)
  ON DELETE RESTRICT;
