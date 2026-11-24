package com.ease2k.blog.owner;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

interface OwnerEntryMapper {

    @Select("SELECT payload, revision, deleted, updated_at FROM entries")
    List<EntryRow> entries();

    @Select("SELECT payload, revision, deleted, updated_at FROM entries WHERE deleted=1 ORDER BY updated_at DESC")
    List<EntryRow> trash();

    @Select("SELECT payload, revision, deleted, updated_at FROM entries WHERE id=#{id}")
    EntryRow entry(@Param("id") String id);

    @Select("SELECT EXISTS (SELECT 1 FROM entries WHERE id=#{id})")
    boolean exists(@Param("id") String id);

    @Insert("""
            INSERT OR REPLACE INTO entries (id, payload, revision, deleted, updated_at)
            VALUES (#{id}, #{payload}, #{revision}, #{deleted}, #{updatedAt})
            """)
    void saveEntry(@Param("id") String id, @Param("payload") String payload, @Param("revision") int revision,
                   @Param("deleted") boolean deleted, @Param("updatedAt") String updatedAt);

    @Select("SELECT payload, revision, deleted, saved_at FROM history WHERE id=#{id} ORDER BY revision DESC")
    List<HistoryRow> history(@Param("id") String id);

    @Select("SELECT payload FROM history")
    List<String> historyPayloads();

    @Select("""
            SELECT json_extract(payload, '$.date') FROM history
            WHERE id=#{id} AND json_extract(payload, '$.visibility')='public' ORDER BY revision LIMIT 1
            """)
    String firstPublishedDate(@Param("id") String id);

    @Insert("""
            INSERT OR IGNORE INTO history (id, revision, payload, deleted, saved_at)
            VALUES (#{id}, 0, #{payload}, 0, #{savedAt})
            """)
    void seedHistory(@Param("id") String id, @Param("payload") String payload, @Param("savedAt") String savedAt);

    @Insert("""
            INSERT INTO history (id, revision, payload, deleted, saved_at)
            VALUES (#{id}, #{revision}, #{payload}, #{deleted}, #{savedAt})
            """)
    void appendHistory(@Param("id") String id, @Param("revision") int revision, @Param("payload") String payload,
                       @Param("deleted") boolean deleted, @Param("savedAt") String savedAt);

    @Delete("DELETE FROM history WHERE id=#{id} AND revision < #{revision}")
    void pruneHistory(@Param("id") String id, @Param("revision") int revision);

    @Select("SELECT payload, base_revision, creating, revision, saved_at FROM working_copies ORDER BY saved_at DESC")
    List<DraftRow> drafts();

    @Select("SELECT COALESCE((SELECT revision FROM working_copies WHERE id=#{id}), 0)")
    int draftRevision(@Param("id") String id);

    @Insert("""
            INSERT OR REPLACE INTO working_copies (id, payload, base_revision, creating, revision, saved_at)
            VALUES (#{id}, #{payload}, #{baseRevision}, #{creating}, #{revision}, #{savedAt})
            """)
    void saveDraft(@Param("id") String id, @Param("payload") String payload, @Param("baseRevision") int baseRevision,
                   @Param("creating") boolean creating, @Param("revision") int revision, @Param("savedAt") String savedAt);

    @Update("""
            UPDATE working_copies SET payload=#{payload}, base_revision=#{baseRevision},
                revision=revision+1, saved_at=#{savedAt} WHERE id=#{id}
            """)
    void reviseDraft(@Param("id") String id, @Param("payload") String payload,
                     @Param("baseRevision") int baseRevision, @Param("savedAt") String savedAt);

    @Delete("DELETE FROM working_copies WHERE id=#{id}")
    void deleteDraft(@Param("id") String id);

    record EntryRow(String payload, int revision, boolean deleted, String updatedAt) {
    }

    record HistoryRow(String payload, int revision, boolean deleted, String savedAt) {
    }

    record DraftRow(String payload, int baseRevision, boolean creating, int revision, String savedAt) {
    }
}
