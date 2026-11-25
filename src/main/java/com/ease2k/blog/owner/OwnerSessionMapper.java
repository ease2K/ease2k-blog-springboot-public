package com.ease2k.blog.owner;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

interface OwnerSessionMapper {

    @Select("SELECT EXISTS (SELECT 1 FROM sessions WHERE hash=#{hash} AND version=#{version} AND expires > #{now})")
    boolean active(@Param("hash") String hash, @Param("version") String version, @Param("now") long now);

    @Delete("DELETE FROM attempts WHERE started <= #{cutoff} OR version <> #{version}")
    void pruneAttempts(@Param("cutoff") long cutoff, @Param("version") String version);

    @Insert("""
            INSERT INTO attempts (version, started, count) VALUES (#{version}, #{now}, 0)
            ON CONFLICT (version) DO NOTHING
            """)
    void startAttempts(@Param("version") String version, @Param("now") long now);

    @Update("UPDATE attempts SET count=count+1 WHERE version=#{version} AND count < 8")
    int addAttempt(@Param("version") String version);

    @Delete("DELETE FROM attempts WHERE version=#{version}")
    void clearAttempts(@Param("version") String version);

    @Delete("DELETE FROM sessions WHERE expires <= #{now} OR version <> #{version}")
    void pruneSessions(@Param("now") long now, @Param("version") String version);

    @Insert("INSERT INTO sessions (hash, version, expires) VALUES (#{hash}, #{version}, #{expires})")
    void addSession(@Param("hash") String hash, @Param("version") String version, @Param("expires") long expires);

    @Delete("DELETE FROM sessions WHERE hash=#{hash}")
    void deleteSession(@Param("hash") String hash);
}
