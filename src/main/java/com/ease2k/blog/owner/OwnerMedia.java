package com.ease2k.blog.owner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** 站主图片管理和公开页面所需的文件查找。 */
public interface OwnerMedia {

    ObjectNode upload(InputStream input, String label) throws IOException;

    /** 找到文件时返回其路径，缺少文件时返回 null。 */
    Path file(String name) throws IOException;

    List<ObjectNode> list();

    void edit(String name, JsonNode input);

    int replace(String name, JsonNode input);
}
