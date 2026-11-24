package com.ease2k.blog.owner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** 站主文章、草稿与修订记录的访问契约。 */
public interface OwnerEntries {

    List<ObjectNode> published();

    List<ObjectNode> all();

    List<ObjectNode> trash();

    List<ObjectNode> series();

    List<ObjectNode> drafts();

    List<ObjectNode> history(String id);

    ObjectNode write(String id, JsonNode input);

    void trash(String id, JsonNode input);

    ObjectNode restore(String id, JsonNode input);

    ObjectNode saveDraft(String id, JsonNode input);

    /** 执行一次文章变更，调用方负责开启写事务。 */
    ObjectNode save(String id, ObjectNode input, int revision, boolean create, boolean restore,
                    boolean remove, boolean recordUpdate, boolean mediaOnly);
}
