#!/usr/bin/env bash
set -euo pipefail

for var in FIXCAMPUS_DB_PASSWORD FIXCAMPUS_MIGRATION_PASSWORD; do
  val="${!var:-}"
  if [[ -z "$val" || "$val" == REPLACE_WITH_* ]]; then
    echo "ERROR: $var must be set to a real local password in .env" >&2
    exit 1
  fi
  if [[ "$val" == *"'"* || "$val" == *'\'* ]]; then
    echo "ERROR: $var must not contain single quotes or backslashes" >&2
    exit 1
  fi
done

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS \`fixcampus_m421_docker_test\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'fixcampus_app'@'%' IDENTIFIED BY '${FIXCAMPUS_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'fixcampus_migration'@'%' IDENTIFIED BY '${FIXCAMPUS_MIGRATION_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE
  ON \`fixcampus\_m421\_docker\_test\`.* TO 'fixcampus_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON \`fixcampus\_m421\_docker\_test\`.* TO 'fixcampus_migration'@'%';
FLUSH PRIVILEGES;
SQL
