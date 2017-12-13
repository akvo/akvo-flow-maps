CREATE TABLE tenant (
    id serial,
    tenant text NOT NULL UNIQUE,
    db_uri text NOT NULL,
    db_creation_state text  NOT NULL
);