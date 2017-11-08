CREATE TABLE tenant (
    id serial,
    tenant text NOT NULL UNIQUE,
    database text NOT NULL,
    username text NOT NULL,
    password text NOT NULL,
    db_uri text,
    db_creation_state text  NOT NULL
);