# Database

`migrations/` is the single source of Flyway schema migrations. Maven packages it into `db/migration`. Fresh deployments apply all versions automatically. Do not edit an already-applied migration: add a new numbered migration.

Runtime MySQL, Redis and object storage data lives in Docker named volumes, never in Git. `docker compose down` keeps data; `docker compose down -v` deletes it. Back up `.env` together with database/object storage volumes: encrypted provider credentials require the original master key.
