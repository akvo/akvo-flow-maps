CREATE TABLE tenants (
    id serial,
    tenant text NOT NULL UNIQUE,
    username text,
    password text,
    db_uri text,
    db_creation_state text
);