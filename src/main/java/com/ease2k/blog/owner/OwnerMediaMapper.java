package com.ease2k.blog.owner;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

interface OwnerMediaMapper {

    @Select("SELECT name, label, deleted, revision, created_at FROM media_assets ORDER BY created_at DESC")
    List<Asset> assets();

    @Select("SELECT name, label, deleted, revision, created_at FROM media_assets WHERE name=#{name}")
    Asset asset(@Param("name") String name);

    @Select("SELECT EXISTS (SELECT 1 FROM media_assets WHERE name=#{name} AND deleted=0)")
    boolean active(@Param("name") String name);

    @Select("SELECT EXISTS (SELECT 1 FROM media_assets WHERE name=#{name} AND deleted=1)")
    boolean deleted(@Param("name") String name);

    @Update("UPDATE media_assets SET label=#{label}, deleted=#{deleted}, revision=revision+1 WHERE name=#{name}")
    void edit(@Param("name") String name, @Param("label") String label, @Param("deleted") boolean deleted);

    @Insert("""
            INSERT OR IGNORE INTO media_assets (name, label, deleted, revision, created_at)
            VALUES (#{name}, #{label}, 0, 1, #{createdAt})
            """)
    void register(@Param("name") String name, @Param("label") String label, @Param("createdAt") String createdAt);

    record Asset(String name, String label, boolean deleted, int revision, String createdAt) {
    }
}
