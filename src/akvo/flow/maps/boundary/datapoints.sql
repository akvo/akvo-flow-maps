-- :name upsert-datapoint :!
insert into datapoint(id, survey_id, created_date_time, last_update_date_time, geom)
    values (:id, :survey-id, :created-date-time, :last-update-date-time, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
    ON conflict(id)
    do update
    set (survey_id, created_date_time, last_update_date_time, geom)
      = (:survey-id, :created-date-time, :last-update-date-time, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326))
    where datapoint.id = :id