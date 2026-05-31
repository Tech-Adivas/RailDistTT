-- Create per-service databases in local dev.
-- In production each service has its own RDS instance.
-- The timetable service requires the railway_replication role for Debezium.

CREATE DATABASE timetable_db;
CREATE DATABASE schedule_db;
CREATE DATABASE query_db;
CREATE DATABASE distribution_db;
CREATE DATABASE notification_db;

-- Create a replication user for Debezium CDC.
-- In production, use a dedicated IAM-authenticated RDS user.
CREATE USER debezium_user WITH REPLICATION LOGIN PASSWORD 'debezium_dev_password';  -- DEV-ONLY password

-- Grant the replication user read access to the timetable database.
GRANT CONNECT ON DATABASE timetable_db TO debezium_user;

\c timetable_db
GRANT USAGE ON SCHEMA public TO debezium_user;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO debezium_user;

-- Create a publication for the outbox table (Debezium pgoutput plugin).
-- This is also created by Flyway migration, but we add it here for the
-- initial local dev setup before Flyway has run.
-- The publication is created in the timetable service Flyway migration V1__init.sql.
