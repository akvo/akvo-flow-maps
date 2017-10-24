CREATE TABLE IF NOT EXISTS datapoint (
    id text PRIMARY KEY,
    survey_id text,
    last_update_date_time timestamptz,
    created_date_time timestamptz);


SELECT AddGeometryColumn('datapoint','geom','4326','POINT',2);

CREATE INDEX ON datapoint USING GIST(geom);