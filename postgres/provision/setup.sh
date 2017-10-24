#!/usr/bin/env bash

set -e

CLI_ERR_MSG="Postgres CLI tools not available (psql). Using Postgres.app, look
at http://postgresapp.com/documentation/cli-tools.html. Aborting."
hash psql 2>/dev/null || { echo >&2 $CLI_ERR_MSG ; exit 1; }

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd $DIR


# Provision

## Create lumen role
psql -c "CREATE ROLE dbuser WITH PASSWORD 'dbpassword' CREATEDB LOGIN;"
psql -c "CREATE ROLE a_tenant_user WITH PASSWORD 'a_tenant_password' CREATEDB LOGIN;"

## Create lumen dbs
psql -f $DIR/helpers/create-database.sql

## Create extensions for dbs
psql -d a_tenant_db -f $DIR/helpers/create-extensions.sql
psql -U a_tenant_user -d a_tenant_db -f $DIR/helpers/a-tenant-db.sql

echo ""
echo "----------"
echo "Done!"

cp pg.conf /var/lib/postgresql/data/postgresql.conf
#cp pg_hba.conf /var/lib/postgresql/data/pg_hba.conf
cp server.crt /var/lib/postgresql/data/
cp server.key /var/lib/postgresql/data/

chmod 0600 /var/lib/postgresql/data/server.key