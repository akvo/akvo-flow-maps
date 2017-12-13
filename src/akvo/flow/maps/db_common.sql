-- :name get-tenant-credentials :? :1
select tenant, db_uri, db_creation_state from tenant where tenant = :tenant