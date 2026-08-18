# CoreIntra — one box, one make target.
#
# Everything here works identically vendor-managed and on-prem; only .env differs.

SHELL := /bin/bash
COMPOSE := docker compose
BACKUP_DIR ?= ./backups

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

.env:
	@echo "No .env found. Copy .env.example to .env and set the passwords first." >&2
	@echo "  cp .env.example .env" >&2
	@exit 1

.PHONY: up
up: .env ## Start the whole stack
	$(COMPOSE) up -d --build
	@echo "CoreIntra starting. Logs: make logs"

.PHONY: down
down: ## Stop the stack, keeping data
	$(COMPOSE) down

.PHONY: logs
logs: ## Tail logs from every service
	$(COMPOSE) logs -f --tail=100

.PHONY: ps
ps: ## Show service status
	$(COMPOSE) ps

.PHONY: restart-conversion
restart-conversion: ## Restart only the conversion worker
	@# A wedged conversion worker degrades exports; it must never require a
	@# full-stack restart to clear.
	$(COMPOSE) restart conversion-worker

.PHONY: backup
backup: .env ## Write a single restorable tarball (database + blobs + fonts + config)
	@./ops/backup.sh "$(BACKUP_DIR)"

.PHONY: restore
restore: .env ## Restore from a tarball: make restore TARBALL=backups/xyz.tar.gz
	@test -n "$(TARBALL)" || { echo "Usage: make restore TARBALL=backups/<file>.tar.gz" >&2; exit 1; }
	@./ops/restore.sh "$(TARBALL)"

.PHONY: verify
verify: ## Run every check CI runs
	./ops/verify-java8-gate.sh
	cd backend && ./mvnw -B verify
	cd frontend && pnpm install --frozen-lockfile && pnpm test && pnpm build

.PHONY: test-backend
test-backend: ## Backend tests only
	cd backend && ./mvnw -B test

.PHONY: test-frontend
test-frontend: ## Frontend tests only
	cd frontend && pnpm test

.PHONY: seed
seed: ## Load the demo company (two entities, 공동대표, ~40 employees)
	@# A second JVM inside the running api container, so it shares the database
	@# and the encryption key without needing either on the host. It must not
	@# start a web server: the port is already taken by the api it is running
	@# inside, and a seed that fails on "address already in use" tells you
	@# nothing about the seed. With no web server the process exits when the
	@# runner returns — zero when the demo is loaded, non-zero with the reason
	@# when it is not.
	$(COMPOSE) exec api java -jar /app/app.jar \
		--spring.profiles.active=seed \
		--spring.main.web-application-type=none
