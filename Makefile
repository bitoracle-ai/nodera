# Nodera — one entry point for every routine task.
#
# `make check` runs everything CI runs. If it is green here it is green there, with two
# exceptions worth knowing: the backend tests need a running Docker daemon (Testcontainers),
# and skipping them locally is the most common cause of a surprise red build; and the secret
# scan (gitleaks) stays CI-only — `make check` does not invoke the gitleaks binary. Run
# `gitleaks detect --config .gitleaks.toml` yourself if you have it installed (docs/ci.md).

.DEFAULT_GOAL := help
SHELL := /bin/sh

PY ?= python
COMPOSE ?= docker compose

# Local development values, deliberately literal and unmistakably local — the same reasoning
# as the password in docker-compose.yml. A Makefile that reads them from the environment
# teaches contributors to put real credentials in shell history. The application itself has no
# defaults: it refuses to start without these (invariant #6), which is why they are named here
# rather than buried in a fallback nobody reads.
DEV_DB_ENV  = NODERA_DB_URL=jdbc:postgresql://localhost:5432/nodera NODERA_DB_USER=nodera NODERA_DB_PASSWORD=nodera-local-dev-only
DEV_APP_ENV = $(DEV_DB_ENV) NODERA_APP_PASSWORD=nodera-local-dev-only

.PHONY: help dev up down logs migrate seed check check-repo check-db verify-db check-backend \
        check-frontend backend frontend test fmt clean ticket

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------
# Development
# ---------------------------------------------------------------------------

dev: up migrate seed ## Start everything: postgres, migrations, seed, backend, frontend
	@echo ""
	@echo "  API      http://localhost:8080"
	@echo "  Web      http://localhost:5173"
	@echo ""
	@$(MAKE) -j2 backend frontend

up: ## Start Postgres only
	$(COMPOSE) up -d postgres
	@$(COMPOSE) exec -T postgres sh -c 'until pg_isready -U nodera >/dev/null 2>&1; do sleep 1; done'
	@echo "postgres ready"

down: ## Stop everything and remove the containers (keeps the volume)
	$(COMPOSE) down

logs: ## Follow the Postgres log
	$(COMPOSE) logs -f postgres

migrate: ## Apply migrations — the same code path the `migrate` entrypoint runs in the image
	cd backend && $(DEV_APP_ENV) ./gradlew :app:run --args=migrate --no-daemon

seed: ## Load the development seed (one project, one human, one agent)
	@$(COMPOSE) exec -T postgres psql -U nodera -d nodera -v ON_ERROR_STOP=1 -q < db/seed/dev-seed.sql
# stdin rather than `-f /seed/...`: a POSIX path handed to a container from Git Bash on
# Windows is rewritten to a host path (C:/Program Files/Git/seed/...) and psql cannot find
# it. Piping keeps the same command working on every contributor's machine.

backend: ## Run the backend
	cd backend && $(DEV_DB_ENV) NODERA_STATIC_ROOT=../../frontend/dist ./gradlew :app:run --no-daemon

frontend: ## Run the frontend dev server
	cd frontend && yarn install --frozen-lockfile && yarn dev

ticket: ## Scaffold a ticket: make ticket ID=CORE-06 T="Title" [P=P2] [E="~1 d"]
	@test -n "$(ID)" || (echo "ID is required, e.g. make ticket ID=CORE-06 T=\"Title\""; exit 1)
	@test -n "$(T)"  || (echo "T (title) is required"; exit 1)
	$(PY) scripts/ticket_new.py "$(ID)" "$(T)" --priority "$(or $(P),P3)" --effort "$(or $(E),~1 d)"

# ---------------------------------------------------------------------------
# Gates — the local equivalents of the CI lanes (docs/ci.md)
# ---------------------------------------------------------------------------

check: check-repo check-db check-backend check-frontend ## Everything CI runs (except the CI-only gitleaks scan)
	@echo ""
	@echo "All gates green."

check-repo: ## Executable bits, line endings, docs, tickets, adapters, language, invariants, release triggers, TODO/FIXME
	$(PY) scripts/lint_executable_bits.py
	$(PY) scripts/lint_line_endings.py
	$(PY) scripts/docs_list.py
	$(PY) scripts/generate_docs_map.py --check
	$(PY) scripts/check_tickets.py --check
	$(PY) scripts/lint_adapters.py
	$(PY) scripts/lint_docs_index.py
	$(PY) scripts/lint_language.py
	$(PY) scripts/lint_workflow_triggers.py
	$(PY) scripts/lint_invariants.py
# The same grep the CI repo-checks lane runs (ci.yml "No TODO/FIXME comments") — keep the
# pattern, includes and paths byte-identical to it, or the two drift. One deliberate delta:
# the --exclude-dir list. CI greps a fresh checkout; a working tree additionally carries the
# git-ignored output directories (node_modules/ alone ships hundreds of third-party TODOs),
# and without the excludes this gate is red on every machine that ever ran `yarn install`.
	@if grep -rInE '(^|[^A-Za-z])(TODO|FIXME)([^A-Za-z]|$$)' \
		--include='*.kt' --include='*.kts' --include='*.ts' --include='*.tsx' \
		--include='*.sql' --include='*.py' \
		--exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist \
		--exclude-dir=coverage --exclude-dir=.gradle \
		backend/ frontend/ db/ scripts/ 2>/dev/null; then \
		echo "TODO/FIXME found. Fix it, drop it with a recorded reason, or open a ticket (docs/PROJECT_MANAGEMENT.md § 8)."; \
		exit 1; \
	fi; \
	echo "OK - no TODO/FIXME comments."

check-db: ## SQL conventions (no database needed)
	$(PY) scripts/lint_sql.py

# Runs against a THROWAWAY database, not the development one. The CI lane always starts from an
# empty Postgres; a contributor's dev database usually is not empty, and pointing this at it would
# either fail confusingly (Flyway refuses a non-empty schema with no history table) or migrate over
# data somebody was using. Created and dropped here, so running it costs nothing and destroys
# nothing.
VERIFY_DB  = nodera_verify
VERIFY_ENV = NODERA_DB_URL=jdbc:postgresql://localhost:5432/$(VERIFY_DB) NODERA_DB_USER=nodera              NODERA_DB_PASSWORD=nodera-local-dev-only NODERA_APP_PASSWORD=nodera-local-dev-only

verify-db: up ## What the CI database lane does: apply twice on an empty database, then the checks
	@$(COMPOSE) exec -T postgres psql -U nodera -d postgres -q -c 'drop database if exists $(VERIFY_DB)'
	@$(COMPOSE) exec -T postgres psql -U nodera -d postgres -q -c 'create database $(VERIFY_DB)'
	cd backend && $(VERIFY_ENV) ./gradlew :app:run --args=migrate --no-daemon
# Twice on purpose. A migration that applies once but not twice fails on the next deployment, and
# that is the worst possible moment to find out.
	cd backend && $(VERIFY_ENV) ./gradlew :app:run --args=migrate --no-daemon
	@$(COMPOSE) exec -T postgres psql -U nodera -d $(VERIFY_DB) -v ON_ERROR_STOP=1 -q < db/checks/schema_integrity.sql
	@$(COMPOSE) exec -T postgres psql -U nodera -d postgres -q -c 'drop database $(VERIFY_DB)'

check-backend: ## ktlint, detekt, module boundaries, tests, build (needs Docker)
	cd backend && ./gradlew ktlintCheck detekt checkModuleBoundaries test build --no-daemon

check-frontend: ## install, generated client fresh, lint, types, coverage, build
	cd frontend && yarn install --frozen-lockfile
	cd frontend && yarn api:generate && git diff --exit-code -- src/api/generated
	cd frontend && yarn lint && yarn typecheck && yarn test:coverage && yarn build

test: ## Tests only, both sides
	cd backend && ./gradlew test --no-daemon
	cd frontend && yarn install --frozen-lockfile && yarn test:run

fmt: ## Format everything in place
	cd backend && ./gradlew ktlintFormat --no-daemon
	cd frontend && yarn format

clean: ## Remove build output (not the database volume)
	cd backend && ./gradlew clean --no-daemon
	rm -rf frontend/dist frontend/node_modules/.vite
