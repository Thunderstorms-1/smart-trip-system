-- Independent knowledge management only: no changes to itinerary or account tables.
CREATE TABLE IF NOT EXISTS rag_knowledge_document (
  document_id varchar(120) NOT NULL PRIMARY KEY,
  document_version varchar(80) NOT NULL,
  title varchar(240) NOT NULL,
  original_path varchar(1000) NOT NULL,
  source_name varchar(240) NOT NULL,
  source_url varchar(2000) NOT NULL,
  source_updated_at date NULL,
  metadata_json longtext NOT NULL,
  content_hash char(64) NOT NULL,
  embedding_model varchar(200) NOT NULL,
  embedding_version varchar(80) NOT NULL,
  chunk_count int NOT NULL DEFAULT 0,
  status varchar(20) NOT NULL,
  active_index varchar(200) NULL,
  ingested_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  KEY idx_rag_document_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rag_knowledge_import (
  import_id char(36) NOT NULL PRIMARY KEY,
  operation varchar(20) NOT NULL,
  manifest_hash char(64) NULL,
  status varchar(20) NOT NULL,
  document_count int NOT NULL DEFAULT 0,
  chunk_count int NOT NULL DEFAULT 0,
  physical_index varchar(200) NULL,
  error_code varchar(80) NULL,
  started_at datetime(3) NOT NULL,
  finished_at datetime(3) NULL,
  KEY idx_rag_import_started (started_at),
  KEY idx_rag_import_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;