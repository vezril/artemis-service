# deduplicated-ingest Specification

## Purpose
One post per unique image (md5). A duplicate upload merges into the existing post rather than
creating a second, keeping blob storage 1:1 with posts so deletion stays a direct blob delete.

## Requirements
### Requirement: Enforce md5 uniqueness with merge on duplicate

On upload, Artemis SHALL compute the md5 and, if a **live** post with that md5 already exists,
SHALL NOT create a new post — instead it SHALL **merge** the new upload's metadata into the
existing post: union its tags, add any pool memberships, and append the source, while keeping
the existing rating. Storage therefore stays 1:1 (one post per md5).

#### Scenario: Re-uploading an existing image merges metadata
- **GIVEN** a live post with md5 `abc…` tagged `[1girl]`
- **WHEN** the same image is uploaded again with tags `[cat_ears]`
- **THEN** no new post is created and the existing post's tags become `[1girl, cat_ears]` (union), with its rating unchanged

#### Scenario: Edge case — a genuinely new image creates a post
- **GIVEN** an upload whose md5 matches no existing post
- **WHEN** it is processed
- **THEN** a new post is created normally

### Requirement: Restore on a soft-deleted match

If an uploaded md5 matches a **soft-deleted** post, Artemis SHALL restore that post (and merge
any new metadata) rather than create a new one — re-uploading something you deleted brings it back.

#### Scenario: Uploading a soft-deleted image restores it
- **GIVEN** a soft-deleted post with md5 `abc…`
- **WHEN** that same image is uploaded
- **THEN** the post is restored (un-deleted) and any new metadata is merged in

#### Scenario: Edge case — a purged image is a fresh upload
- **GIVEN** md5 `abc…` whose only post was already purged (gone)
- **WHEN** the image is uploaded
- **THEN** a brand-new post is created (there is nothing to restore)
