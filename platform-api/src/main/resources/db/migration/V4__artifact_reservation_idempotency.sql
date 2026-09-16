ALTER TABLE object_reservations ADD COLUMN idempotency_key CHAR(64);
ALTER TABLE object_reservations ADD CONSTRAINT reservation_idempotency_key_format CHECK (idempotency_key ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX object_reservations_task_idempotency_key ON object_reservations(task_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
