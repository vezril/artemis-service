-- Canonical Pekko Persistence R2DBC Postgres schema (the write-side journal).
-- The runtime plugin does not auto-create tables; integration tests apply this
-- before connecting, and deployment applies the equivalent DDL out of band.
-- Read-model/projection tables (posts, tags, pools) arrive in roadmap M4.

CREATE TABLE IF NOT EXISTS event_journal (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  db_timestamp timestamp with time zone NOT NULL,

  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,

  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  writer VARCHAR(255) NOT NULL,
  adapter_manifest VARCHAR(255),
  tags TEXT ARRAY,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY (persistence_id, seq_nr)
);

CREATE INDEX IF NOT EXISTS event_journal_slice_idx
  ON event_journal (slice, entity_type, db_timestamp, seq_nr, persistence_id);

CREATE TABLE IF NOT EXISTS snapshot (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  write_timestamp BIGINT NOT NULL,
  ser_id INTEGER NOT NULL,
  ser_manifest VARCHAR(255) NOT NULL,
  snapshot BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY (persistence_id)
);

CREATE TABLE IF NOT EXISTS durable_state (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  revision BIGINT NOT NULL,
  db_timestamp timestamp with time zone NOT NULL,

  state_ser_id INTEGER NOT NULL,
  state_ser_manifest VARCHAR(255),
  state_payload BYTEA NOT NULL,
  tags TEXT ARRAY,

  PRIMARY KEY (persistence_id, revision)
);

CREATE INDEX IF NOT EXISTS durable_state_slice_idx
  ON durable_state (slice, entity_type, db_timestamp, revision, persistence_id);

-- ---------------------------------------------------------------------------
-- Read side (roadmap M4): Pekko Projection offset store + query-optimized read
-- model. The projections tail the Post/Pool journals into these tables; the
-- search DSL and the API read only from here, never from the entities. All read
-- tables are rebuildable: drop them and replay the journal from offset zero.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS projection_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  current_offset VARCHAR(255) NOT NULL,
  manifest VARCHAR(32) NOT NULL,
  mergeable BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY (projection_name, projection_key)
);

CREATE TABLE IF NOT EXISTS projection_timestamp_offset_store (
  slice INT NOT NULL,
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  timestamp_offset timestamp with time zone NOT NULL,
  timestamp_consumed timestamp with time zone NOT NULL,
  PRIMARY KEY (slice, projection_name, timestamp_offset, persistence_id, seq_nr)
);

CREATE TABLE IF NOT EXISTS projection_management (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  paused BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY (projection_name, projection_key)
);

-- Trigram matching for tag-name autocomplete.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- posts: one row per post, the denormalized fields the search DSL queries. `tags`
-- is a GIN-indexed text[] for array-intersection tag search; btrees back the
-- metatag predicates (score/rating/status/created_at/md5). `derivatives` holds the
-- Apollo media refs as JSON. `status` ∈ {pending, active, failed, deleted}.
CREATE TABLE IF NOT EXISTS posts (
  id          VARCHAR(255) PRIMARY KEY,
  tags        TEXT[] NOT NULL DEFAULT '{}',
  rating      VARCHAR(1),
  score       INT NOT NULL DEFAULT 0,
  fav_count   INT NOT NULL DEFAULT 0,
  width       INT,
  height      INT,
  duration    BIGINT,
  filetype    VARCHAR(255),
  md5         VARCHAR(64),
  phash       VARCHAR(255),
  parent_id   VARCHAR(255),
  source      TEXT,
  status      VARCHAR(16) NOT NULL,
  derivatives JSONB NOT NULL DEFAULT '[]',
  created_at  timestamp with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS posts_tags_gin ON posts USING GIN (tags);
CREATE INDEX IF NOT EXISTS posts_score_idx ON posts (score);
CREATE INDEX IF NOT EXISTS posts_rating_idx ON posts (rating);
CREATE INDEX IF NOT EXISTS posts_status_idx ON posts (status);
CREATE INDEX IF NOT EXISTS posts_created_at_idx ON posts (created_at);
CREATE INDEX IF NOT EXISTS posts_md5_idx ON posts (md5);

-- tags: projection-maintained membership counts, trigram-indexed for autocomplete.
-- `category` defaults to 0 (general); real category assignment arrives with the
-- tag-relationships milestone (M2).
CREATE TABLE IF NOT EXISTS tags (
  name       VARCHAR(255) PRIMARY KEY,
  category   INT NOT NULL DEFAULT 0,
  post_count INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS tags_name_trgm ON tags USING GIN (name gin_trgm_ops);

-- pools + ordered membership.
CREATE TABLE IF NOT EXISTS pools (
  id   VARCHAR(255) PRIMARY KEY,
  name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS pool_posts (
  pool_id  VARCHAR(255) NOT NULL,
  post_id  VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  PRIMARY KEY (pool_id, post_id)
);

CREATE INDEX IF NOT EXISTS pool_posts_pool_pos ON pool_posts (pool_id, position);
