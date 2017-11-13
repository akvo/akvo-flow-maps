-- :name insert-tenant :!
insert into tenant (tenant, db_uri, db_creation_state)
       VALUES (:tenant, :db-uri, :db-creation-state) on conflict(tenant) do nothing

-- :name get-tenant-credentials :? :1
select tenant, db_uri from tenant where tenant = :tenant

-- :name update-tenant-state :!
update tenant set db_creation_state = :db-creation-state where tenant = :tenant

-- :name load-tenant-credentials :?
select tenant, db_uri from tenant

-- :name create-db :!
CREATE DATABASE :i:dbname WITH OWNER = :i:owner
    TEMPLATE = template0
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'

-- :name create-extensions :!
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS tablefunc WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

-- :name create-role :!
CREATE ROLE :i:username WITH PASSWORD
--~ (str "'" (:password params) "'")
LOGIN;

-- :name create-db-tables :!
CREATE TABLE IF NOT EXISTS datapoint (
    identifier text PRIMARY KEY,
    survey_id text,
    last_update_date_time timestamptz,
    created_date_time timestamptz);

-- :name add-geom-column
SELECT AddGeometryColumn('datapoint','geom','4326','POINT',2);

-- :name create-indices :!
CREATE INDEX ON datapoint USING GIST(geom);