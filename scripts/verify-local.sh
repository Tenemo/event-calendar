#!/usr/bin/env bash
set -euo pipefail

./mvnw clean test package
docker compose up -d postgres
curl -i http://localhost:9080/health
docker compose exec postgres psql -U calendar -d calendar -c 'select current_database(), current_user;'
