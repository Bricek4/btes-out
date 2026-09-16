# Contributing

Use JDK 21 and the checked-in Maven Wrapper. Run `./mvnw test` before opening a change.

`contracts` contains stable DTOs, task states, and the OpenAPI document. Services may depend on
`contracts`; contracts must not depend on a service. Keep provider secrets, raw prompts, and raw
completions out of source, logs, task events, and tests.

Place PostgreSQL changes in `platform-api/src/main/resources/db/migration` as immutable Flyway
migrations. Do not alter an applied migration; add a new versioned file instead.
