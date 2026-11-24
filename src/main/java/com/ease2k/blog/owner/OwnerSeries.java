package com.ease2k.blog.owner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 保存专栏信息及章节顺序。 */
public interface OwnerSeries {

    ObjectNode save(JsonNode input);
}
