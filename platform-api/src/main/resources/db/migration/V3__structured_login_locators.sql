ALTER TABLE login_profiles
  ALTER COLUMN username_locator TYPE JSONB USING jsonb_build_object('kind','label','name',username_locator),
  ALTER COLUMN password_locator TYPE JSONB USING jsonb_build_object('kind','label','name',password_locator),
  ALTER COLUMN submit_locator TYPE JSONB USING jsonb_build_object('kind','label','name',submit_locator);
