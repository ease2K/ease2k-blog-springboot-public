package com.ease2k.blog.owner;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

interface OwnerSeriesMapper {

    @Select("SELECT payload, revision, updated_at FROM series ORDER BY id")
    List<SeriesRow> series();

    @Select("SELECT payload FROM series WHERE id=#{id}")
    String payload(@Param("id") String id);

    @Insert("INSERT OR IGNORE INTO series (id, payload, revision, updated_at) VALUES (#{id}, #{payload}, 1, #{updatedAt})")
    void seedSeries(@Param("id") String id, @Param("payload") String payload, @Param("updatedAt") String updatedAt);

    @Insert("INSERT INTO series (id, payload, revision, updated_at) VALUES (#{id}, #{payload}, 1, #{updatedAt})")
    void insertSeries(@Param("id") String id, @Param("payload") String payload, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT OR REPLACE INTO series (id, payload, revision, updated_at)
            VALUES (#{id}, #{payload}, #{revision}, #{updatedAt})
            """)
    void saveSeries(@Param("id") String id, @Param("payload") String payload,
                    @Param("revision") int revision, @Param("updatedAt") String updatedAt);

    @Update("UPDATE series SET payload=#{payload}, revision=revision+1, updated_at=#{updatedAt} WHERE id=#{id}")
    void reviseSeries(@Param("id") String id, @Param("payload") String payload, @Param("updatedAt") String updatedAt);

    record SeriesRow(String payload, int revision, String updatedAt) {
    }
}
