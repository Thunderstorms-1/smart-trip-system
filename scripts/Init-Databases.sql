-- Run deliberately with a local MySQL account allowed to create demo databases.
-- Does not read or alter the original smart_trip database.
CREATE DATABASE IF NOT EXISTS smart_trip_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS smart_trip_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
