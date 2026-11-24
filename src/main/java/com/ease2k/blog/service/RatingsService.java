package com.ease2k.blog.service;

import tools.jackson.databind.JsonNode;

/** 公开竞赛 Rating 的读取契约。 */
public interface RatingsService {

    JsonNode snapshot();
}
